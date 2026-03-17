package forge.core

import forge.ForgeConfig
import forge.files.FileProcessor
import forge.llm.ModelSelector
import forge.llm.OllamaClient
import forge.llm.PromptBuilder
import forge.workspace.WorkspaceManager
import java.nio.file.Files
import java.nio.file.Path

/**
 * The main execution engine of Forge. Ties together intent resolution,
 * workspace management, the retrieval pipeline, and LLM invocation.
 *
 * Call [execute] with user input and a repository path to get a complete
 * analysis or code generation result.
 */
class Orchestrator(
    private val config: ForgeConfig,
    private val ollamaClient: OllamaClient,
    private val workspaceManager: WorkspaceManager,
    private val modelSelector: ModelSelector,
    private val promptBuilder: PromptBuilder,
    private val fileProcessor: FileProcessor,
    private val stateManager: StateManager
) {
    private val intentResolver = IntentResolver(ollamaClient, promptBuilder, config)

    /**
     * Executes the full Forge pipeline for a given user input against a repository.
     *
     * Steps:
     * 1. Process any attached files (extract text, tables, and image descriptions).
     * 2. Resolve intent from user input (classify task type via LLM).
     * 3. Get or create a workspace for the repository.
     * 4. Register the task in the workspace database.
     * 5. Build the pipeline for the resolved task type.
     * 6. Execute each pipeline stage in order, recording trace entries.
     * 7. Return the final result with response, model info, and trace.
     *
     * The pipeline is interruptible: [StateManager.checkPauseOrStop] is called
     * between stages, allowing the user to pause or stop execution via Ctrl+C.
     *
     * @param userInput     the raw user query or command
     * @param repoPath      path to the repository to analyze
     * @param attachedFiles optional list of file paths to include as additional context
     * @return [ForgeResult] with the LLM response and execution metadata
     */
    suspend fun execute(
        userInput: String,
        repoPath: Path,
        attachedFiles: List<Path> = emptyList(),
        focusModule: String? = null
    ): ForgeResult {
        val trace = mutableListOf<TraceEntry>()
        stateManager.clear()

        // ── Step 1: Process attached files ──────────────────────────────────
        val attachedContents = processAttachedFiles(attachedFiles, trace)

        // ── Step 2: Resolve intent ──────────────────────────────────────────
        val intentStart = System.currentTimeMillis()
        val intent = resolveIntentSafely(userInput)
        val intentDuration = System.currentTimeMillis() - intentStart
        trace.add(TraceEntry(
            "INTENT",
            "Resolved: ${intent.taskType.displayName} (confidence: ${"%.2f".format(intent.confidence)})",
            intentDuration
        ))

        // ── Step 3: Get or create workspace ─────────────────────────────────
        val workspaceStart = System.currentTimeMillis()
        val workspace = workspaceManager.getOrCreate(repoPath)
        val db = workspace.db
        val workspaceDuration = System.currentTimeMillis() - workspaceStart
        trace.add(TraceEntry("WORKSPACE", "Using workspace at ${workspace.path}", workspaceDuration))

        // ── Step 4: Register the task ───────────────────────────────────────
        db.insertTask(
            id = intent.taskId,
            type = intent.taskType.name,
            intent = userInput,
            repoPath = repoPath.toString()
        )
        db.updateTaskStatus(intent.taskId, "running")

        // ── Step 5: Build pipeline for this task type ───────────────────────
        val pipeline = buildPipeline(intent.taskType)

        // ── Step 6: Create pipeline context ─────────────────────────────────
        val context = PipelineContext(
            workspace = workspace,
            db = db,
            repoPath = repoPath,
            taskType = intent.taskType,
            taskId = intent.taskId,
            userInput = userInput,
            config = config,
            stateManager = stateManager,
            ollama = ollamaClient,
            modelSelector = modelSelector,
            promptBuilder = promptBuilder,
            attachedFileContents = attachedContents,
            focusModule = focusModule
        )

        // ── Step 7: Execute pipeline stages ─────────────────────────────────
        try {
            for (stage in pipeline) {
                stateManager.checkPauseOrStop()

                val stageStart = System.currentTimeMillis()
                try {
                    stage.execute(context)
                } catch (e: StoppedException) {
                    trace.add(TraceEntry(stage.name, "Stopped by user", System.currentTimeMillis() - stageStart))
                    db.updateTaskStatus(intent.taskId, "stopped")
                    throw e
                } catch (e: Exception) {
                    val stageDuration = System.currentTimeMillis() - stageStart
                    trace.add(TraceEntry(stage.name, "Error: ${e.message}", stageDuration))
                    // LLM_CALL is the only truly critical stage -- if it fails,
                    // we cannot produce a meaningful response.
                    if (stage.name == "LLM_CALL") {
                        db.updateTaskStatus(intent.taskId, "failed")
                        return ForgeResult(
                            response = "Error during ${stage.name}: ${e.message}",
                            taskType = intent.taskType,
                            model = context.selectedModel.ifEmpty { "unknown" },
                            trace = trace
                        )
                    }
                    // For non-critical stages, log the error and continue.
                    continue
                }
                val stageDuration = System.currentTimeMillis() - stageStart
                trace.add(TraceEntry(stage.name, stage.description, stageDuration))
            }
        } catch (_: StoppedException) {
            return ForgeResult(
                response = "Pipeline stopped by user.",
                taskType = intent.taskType,
                model = context.selectedModel.ifEmpty { "unknown" },
                trace = trace
            )
        }

        // ── Step 8: Mark task as completed and return result ────────────────
        db.updateTaskStatus(intent.taskId, "completed")

        return ForgeResult(
            response = context.llmResponse,
            taskType = intent.taskType,
            model = context.selectedModel,
            trace = trace
        )
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    /**
     * Processes attached files by extracting text, tables, and image descriptions.
     * Results are returned as a map of filename to extracted content.
     * Errors during processing are captured inline rather than propagated.
     */
    private suspend fun processAttachedFiles(
        attachedFiles: List<Path>,
        trace: MutableList<TraceEntry>
    ): Map<String, String> {
        if (attachedFiles.isEmpty()) return emptyMap()

        val attachedContents = mutableMapOf<String, String>()
        val attachStart = System.currentTimeMillis()

        for (filePath in attachedFiles) {
            try {
                if (Files.exists(filePath) && Files.isRegularFile(filePath)) {
                    val result = fileProcessor.process(filePath)
                    val content = buildString {
                        append(result.text)
                        if (result.tables.isNotEmpty()) {
                            appendLine()
                            append(result.tables.joinToString("\n"))
                        }
                        for (img in result.images) {
                            if (img.description != null) {
                                appendLine()
                                append("[Image: ${img.description}]")
                            }
                        }
                    }
                    attachedContents[filePath.fileName.toString()] = content
                }
            } catch (e: Exception) {
                attachedContents[filePath.fileName.toString()] =
                    "[Error processing file: ${e.message}]"
            }
        }

        val attachDuration = System.currentTimeMillis() - attachStart
        trace.add(TraceEntry("ATTACH", "Processed ${attachedFiles.size} attached file(s)", attachDuration))
        return attachedContents
    }

    /**
     * Resolves intent from user input, falling back to [TaskType.REPO_ANALYSIS]
     * if the LLM classification call fails for any reason.
     */
    private suspend fun resolveIntentSafely(userInput: String): ResolvedIntent {
        return try {
            intentResolver.resolve(userInput)
        } catch (e: StoppedException) {
            throw e
        } catch (_: Exception) {
            ResolvedIntent(
                taskType = TaskType.REPO_ANALYSIS,
                primaryTarget = userInput.take(50),
                confidence = 0.2f,
                taskId = "task-fallback"
            )
        }
    }
}
