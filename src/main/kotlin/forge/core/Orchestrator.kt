package forge.core

import forge.ForgeConfig
import forge.core.prompt.DecompositionTracer
import forge.core.prompt.ExecutionPlanner
import forge.core.prompt.ParallelExecutor
import forge.core.prompt.PromptAnalyzer
import forge.core.prompt.PromptComplexity
import forge.core.prompt.Reconciler
import forge.core.prompt.ResultSynthesizer
import forge.core.prompt.TraceEventType
import forge.evolution.TrainingDataCollector
import forge.evolution.QualityScorer
import forge.evolution.TrainingDataFilter
import forge.files.FileProcessor
import forge.web.TraceEvent
import kotlinx.coroutines.channels.SendChannel
import forge.llm.ModelSelector
import forge.llm.OllamaClient
import forge.llm.PromptBuilder
import forge.ui.ForgeConsole
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
    private val stateManager: StateManager,
    private val console: ForgeConsole? = null
) {
    private val intentResolver = IntentResolver(ollamaClient, promptBuilder, config)
    private val trainingDataCollector: TrainingDataCollector? = if (config.evolution.enabled) {
        TrainingDataCollector(
            config = config.evolution,
            scorer = QualityScorer(),
            filter = TrainingDataFilter(config.evolution.piiFilterEnabled)
        )
    } else null

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
        focusModule: String? = null,
        forceReanalyze: Boolean = false
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

        val result = ForgeResult(
            response = context.llmResponse,
            taskType = intent.taskType,
            model = context.selectedModel,
            trace = trace
        )

        // ── Step 9: Collect training data (non-blocking, errors swallowed) ──
        try {
            trainingDataCollector?.collect(
                db = db,
                userInput = userInput,
                result = result,
                sessionId = intent.taskId
            )
        } catch (_: Exception) {
            // Training data collection must never break the main pipeline
        }

        return result
    }

    /**
     * Streaming variant of [execute] that sends real-time trace events
     * via the provided [traceChannel]. Used by the SSE endpoint.
     * The channel is NOT closed by this method — caller is responsible.
     */
    suspend fun executeWithTrace(
        userInput: String,
        repoPath: Path,
        traceChannel: SendChannel<TraceEvent>,
        focusModule: String? = null,
        forceReanalyze: Boolean = false
    ): ForgeResult {
        val trace = mutableListOf<TraceEntry>()
        val executionStart = System.currentTimeMillis()
        stateManager.clear()

        // ── Intent resolution ────────────────────────────────────────────────
        traceChannel.send(TraceEvent.stageStarted("INTENT", 0, 0, "Resolving intent from query..."))
        val intentStart = System.currentTimeMillis()
        val intent = resolveIntentSafely(userInput)
        val intentDuration = System.currentTimeMillis() - intentStart
        val intentDetail = "Resolved: ${intent.taskType.displayName} (confidence: ${"%.2f".format(intent.confidence)})"
        trace.add(TraceEntry("INTENT", intentDetail, intentDuration))
        traceChannel.send(TraceEvent.stageCompleted("INTENT", intentDuration, intentDetail))
        traceChannel.send(TraceEvent.intentResolved(intent.taskType.displayName, intent.confidence))

        // ── Workspace ────────────────────────────────────────────────────────
        traceChannel.send(TraceEvent.stageStarted("WORKSPACE", 1, 0, "Opening workspace..."))
        val workspaceStart = System.currentTimeMillis()
        val workspace = workspaceManager.getOrCreate(repoPath)
        val db = workspace.db
        val workspaceDuration = System.currentTimeMillis() - workspaceStart
        val wsDetail = "Using workspace at ${workspace.path}"
        trace.add(TraceEntry("WORKSPACE", wsDetail, workspaceDuration))
        traceChannel.send(TraceEvent.stageCompleted("WORKSPACE", workspaceDuration, wsDetail))

        // ── Register task ────────────────────────────────────────────────────
        db.insertTask(id = intent.taskId, type = intent.taskType.name, intent = userInput, repoPath = repoPath.toString())
        db.updateTaskStatus(intent.taskId, "running")

        // ── Build & execute pipeline ─────────────────────────────────────────
        val pipeline = buildPipeline(intent.taskType)
        val totalStages = pipeline.size

        val context = PipelineContext(
            workspace = workspace, db = db, repoPath = repoPath,
            taskType = intent.taskType, taskId = intent.taskId, userInput = userInput,
            config = config, stateManager = stateManager, ollama = ollamaClient,
            modelSelector = modelSelector, promptBuilder = promptBuilder,
            focusModule = focusModule
        )

        // Determine if this is a deep analysis task vs a transformation/planning task
        // Transformation prompts should NOT use per-module deep analysis — they need
        // whole-repo reasoning with the user's instruction as primary input.
        val isTransformationPrompt = isTransformationRequest(userInput)
        val isDeepAnalysis = !isTransformationPrompt && intent.taskType in setOf(
            TaskType.REPO_ANALYSIS,
            TaskType.PROJECT_OVERVIEW,
            TaskType.ARCHITECTURE_REVIEW
        )

        try {
            for ((idx, stage) in pipeline.withIndex()) {
                stateManager.checkPauseOrStop()

                traceChannel.send(TraceEvent.stageStarted(stage.name, idx + 2, totalStages + 2, stage.description))

                val stageStart = System.currentTimeMillis()
                try {
                    if (stage.name == "LLM_CALL" && isDeepAnalysis) {
                        // ── Deep multi-pass analysis (module-by-module) ─────
                        executeDeepAnalysis(context, traceChannel, forceReanalyze)
                    } else if (stage.name == "LLM_CALL" && isTransformationPrompt) {
                        // ── Synthesis mode: use heavy SYNTHESIZE model ──────
                        // Transformation/architecture tasks get gpt-oss:20b
                        context.selectedModel = modelSelector.selectForRole(ModelRole.SYNTHESIZE)
                        executeStreamingLlmCall(context, traceChannel)
                    } else if (stage.name == "LLM_CALL") {
                        // ── Standard streaming LLM call ─────────────────────
                        executeStreamingLlmCall(context, traceChannel)
                    } else {
                        stage.execute(context)
                    }
                } catch (e: StoppedException) {
                    trace.add(TraceEntry(stage.name, "Stopped by user", System.currentTimeMillis() - stageStart))
                    traceChannel.send(TraceEvent.error(stage.name, "Stopped by user"))
                    db.updateTaskStatus(intent.taskId, "stopped")
                    throw e
                } catch (e: Exception) {
                    val stageDuration = System.currentTimeMillis() - stageStart
                    trace.add(TraceEntry(stage.name, "Error: ${e.message}", stageDuration))
                    traceChannel.send(TraceEvent.stageCompleted(stage.name, stageDuration, "Error: ${e.message}"))

                    if (stage.name == "LLM_CALL") {
                        db.updateTaskStatus(intent.taskId, "failed")
                        traceChannel.send(TraceEvent.error(stage.name, e.message ?: "LLM call failed"))
                        val result = ForgeResult(
                            response = "Error during ${stage.name}: ${e.message}",
                            taskType = intent.taskType,
                            model = context.selectedModel.ifEmpty { "unknown" },
                            trace = trace
                        )
                        traceChannel.send(TraceEvent.done(result.response, result.taskType.displayName, result.model, System.currentTimeMillis() - executionStart))
                        return result
                    }
                    continue
                }

                val stageDuration = System.currentTimeMillis() - stageStart
                // Emit enriched stage details based on what happened
                val stageDetail = getStageDetail(stage.name, context, stage.description)
                trace.add(TraceEntry(stage.name, stageDetail, stageDuration))
                traceChannel.send(TraceEvent.stageCompleted(stage.name, stageDuration, stageDetail))
            }
        } catch (_: StoppedException) {
            val result = ForgeResult(
                response = "Pipeline stopped by user.",
                taskType = intent.taskType,
                model = context.selectedModel.ifEmpty { "unknown" },
                trace = trace
            )
            traceChannel.send(TraceEvent.done(result.response, result.taskType.displayName, result.model, System.currentTimeMillis() - executionStart))
            return result
        }

        // ── Complete ─────────────────────────────────────────────────────────
        db.updateTaskStatus(intent.taskId, "completed")

        val result = ForgeResult(
            response = context.llmResponse,
            taskType = intent.taskType,
            model = context.selectedModel,
            trace = trace
        )

        // Training data collection
        try {
            trainingDataCollector?.collect(db = db, userInput = userInput, result = result, sessionId = intent.taskId)
        } catch (_: Exception) { }

        traceChannel.send(TraceEvent.done(result.response, result.taskType.displayName, result.model, System.currentTimeMillis() - executionStart))
        return result
    }

    /**
     * Executes deep multi-pass analysis for REPO_ANALYSIS / PROJECT_OVERVIEW tasks.
     * Instead of one LLM call, analyzes each module individually, then synthesizes.
     */
    private suspend fun executeDeepAnalysis(
        ctx: PipelineContext,
        traceChannel: SendChannel<TraceEvent>,
        forceReanalyze: Boolean = false
    ) {
        ctx.selectedModel = ctx.modelSelector.selectForTask(ctx.taskType)
        traceChannel.send(TraceEvent.modelSelected(ctx.selectedModel))

        val deepAnalyzer = DeepAnalyzer(config, ollamaClient, modelSelector)
        val result = deepAnalyzer.analyzeDeep(
            db = ctx.db,
            repoPath = ctx.repoPath,
            evidence = ctx.evidenceMap,
            workspacePath = ctx.workspace.path,
            traceChannel = traceChannel,
            clearCache = forceReanalyze,
            userQuery = ctx.userInput
        )

        ctx.llmResponse = result.synthesis
    }

    /**
     * Executes the LLM call stage with token-level streaming via the trace channel.
     * This replaces the default Pipeline LLM_CALL stage when streaming is active.
     */
    private suspend fun executeStreamingLlmCall(
        ctx: PipelineContext,
        traceChannel: SendChannel<TraceEvent>
    ) {
        // Select model
        ctx.selectedModel = ctx.modelSelector.selectForTask(ctx.taskType)
        traceChannel.send(TraceEvent.modelSelected(ctx.selectedModel))

        // Build evidence strings
        val evidenceStrings = ctx.evidenceMap.map { (key, value) -> "[$key] $value" }

        // Build prompt
        val messages = ctx.promptBuilder.buildTaskPrompt(
            taskType = ctx.taskType,
            evidence = evidenceStrings,
            chunks = ctx.contextChunks,
            fileContents = ctx.fileContents
        )

        // Stream tokens
        val responseBuilder = StringBuilder()
        ctx.ollama.chatStream(ctx.selectedModel, messages).collect { token ->
            responseBuilder.append(token)
            traceChannel.send(TraceEvent.llmToken(token))
        }

        ctx.llmResponse = responseBuilder.toString()
    }

    /**
     * Returns enriched detail text for a pipeline stage based on post-execution state.
     */
    private fun getStageDetail(stageName: String, ctx: PipelineContext, defaultDetail: String): String {
        return when (stageName) {
            "SCAN" -> {
                val fileCount = try { ctx.db.getFileCount() } catch (_: Exception) { 0 }
                "Scanned $fileCount files"
            }
            "CHUNK" -> {
                val chunkCount = try { ctx.db.getChunkCount() } catch (_: Exception) { 0 }
                "Created $chunkCount chunks"
            }
            "EMBED" -> {
                val totalChunks = try { ctx.db.getChunkCount() } catch (_: Exception) { 0 }
                val embeddedCount = try { ctx.db.getEmbeddingCount() } catch (_: Exception) { -1 }
                if (embeddedCount >= 0) {
                    "Embedded $embeddedCount of $totalChunks chunks" +
                        if (totalChunks - embeddedCount > 0) " (${totalChunks - embeddedCount} failed/pending)" else ""
                } else {
                    "Embedded $totalChunks chunks"
                }
            }
            "EVIDENCE" -> {
                val evidenceCount = ctx.evidenceMap.size
                val missing = ctx.missingEvidence.size
                "Collected $evidenceCount evidence items" +
                    if (missing > 0) " ($missing missing)" else ""
            }
            "CONTEXT_ASSEMBLY" -> {
                val chunks = ctx.contextChunks.size
                if (chunks > 0) {
                    "Assembled $chunks context chunks via embedding search"
                } else {
                    "Assembled 0 context chunks (embedding search returned no results)"
                }
            }
            "LLM_CALL" -> {
                val responseLen = ctx.llmResponse.length
                "Model: ${ctx.selectedModel}, response: ${responseLen} chars"
            }
            "MODULE_DISCOVERY" -> {
                val moduleCount = try { ctx.db.getModuleCount() } catch (_: Exception) { 0 }
                if (moduleCount > 0) "Found $moduleCount modules" else "No modules discovered"
            }
            else -> defaultDetail
        }
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
     * Detects if the user's prompt is asking for a transformation, migration,
     * or architectural planning task — not just descriptive analysis.
     * These prompts should use whole-repo reasoning, not per-module deep analysis.
     */
    private fun isTransformationRequest(userInput: String): Boolean {
        val lower = userInput.lowercase()
        val transformationKeywords = listOf(
            "план превращения", "план трансформации", "migration plan",
            "transformation plan", "platformization", "превратить в платформу",
            "convert to platform", "turn into platform", "refactor into",
            "restructure", "reorganize", "modular architecture",
            "step by step plan", "пошаговый план", "roadmap",
            "как превратить", "how to transform", "how to convert",
            "architecture plan", "архитектурный план",
            "migration roadmap", "migration strategy",
            "create a plan", "составь план", "build a plan",
            "design a platform", "спроектируй платформу"
        )
        return transformationKeywords.any { it in lower }
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

    // ── Decomposed multi-partition execution ──────────────────────────────────

    /**
     * Executes a prompt through the decomposition layer. For SIMPLE prompts,
     * delegates directly to [execute]. For COMPOUND/MULTI_STAGE prompts,
     * decomposes into partitions, executes them (potentially in parallel),
     * and synthesizes results.
     */
    suspend fun executeDecomposed(
        userInput: String,
        repoPath: Path,
        attachedFiles: List<Path> = emptyList(),
        focusModule: String? = null
    ): ForgeResult {
        // Skip decomposition if disabled
        if (!config.decomposition.enabled) {
            return execute(userInput, repoPath, attachedFiles, focusModule)
        }

        // Create decomposition tracer for this request
        val tracer = DecompositionTracer()

        // Stage 1-3: Prompt intake, recognition, segmentation
        val analyzer = PromptAnalyzer(config, ollamaClient, tracer)
        val analysis = try {
            analyzer.analyze(userInput)
        } catch (_: Exception) {
            return execute(userInput, repoPath, attachedFiles, focusModule)
        }

        console?.showPromptRecognition(analysis.complexity, analysis.partitions.size)

        // Fast path: single partition, use existing pipeline
        if (analysis.complexity == PromptComplexity.SIMPLE) {
            return execute(userInput, repoPath, attachedFiles, focusModule)
        }

        // Stage 4: Build dependency graph / execution plan
        val planner = ExecutionPlanner(tracer)
        val plan = planner.buildPlan(userInput, analysis)
        console?.showExecutionPlan(plan)

        // Stage 7: Execute partitions in parallel
        val executor = ParallelExecutor(
            orchestrator = this,
            stateManager = stateManager,
            console = console ?: ForgeConsole(showTrace = false),
            maxConcurrentLlmCalls = config.decomposition.maxParallelLlmCalls,
            tracer = tracer
        )
        val results = executor.execute(plan, repoPath, attachedFiles)

        // Stage 8: Cross-task reconciliation
        tracer.record(TraceEventType.RECONCILIATION_STARTED, "Reconciling ${results.size} partition result(s)")
        val reconciler = Reconciler(config)
        val reconciliation = reconciler.reconcile(plan, results)
        if (reconciliation.hasIssues) {
            for (c in reconciliation.contradictions) {
                tracer.record(TraceEventType.CONTRADICTION_FOUND, c.description)
            }
            console?.showReconciliation(reconciliation)
        }
        tracer.record(TraceEventType.RECONCILIATION_COMPLETED,
            "Reconciliation complete: ${reconciliation.issueCount} issue(s)")

        // Stage 9: Final synthesis
        tracer.record(TraceEventType.SYNTHESIS_STARTED, "Synthesizing ${results.size} partition result(s)")
        console?.showSynthesisStart()
        val synthesizer = ResultSynthesizer(config, ollamaClient)
        val synthesized = synthesizer.synthesize(plan, results, reconciliation)
        tracer.record(TraceEventType.SYNTHESIS_COMPLETED, "Synthesis complete")

        // Display decomposition trace
        console?.showDecompositionTrace(tracer)

        return ForgeResult(
            response = synthesized,
            taskType = analysis.primaryArchetype.primaryTaskType,
            model = results.values.firstOrNull { it.modelUsed != null }?.modelUsed ?: "unknown",
            trace = emptyList() // Full decomposition trace available via tracer
        )
    }
}
