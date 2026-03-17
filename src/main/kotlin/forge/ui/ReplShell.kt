package forge.ui

import forge.ForgeConfig
import forge.core.Orchestrator
import forge.core.StoppedException
import forge.files.FileProcessor
import forge.llm.OllamaClient
import forge.voice.WhisperTranscriber
import forge.voice.isMicrophoneAvailable
import forge.voice.isModelDownloaded
import forge.voice.recordUntilSilence
import forge.workspace.WorkspaceManager
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Interactive REPL (Read-Eval-Print Loop) shell for Forge. Reads user
 * input from stdin, supports slash commands, and executes queries through
 * the [Orchestrator].
 *
 * Supported commands:
 *   /voice             - Record audio and transcribe with Whisper
 *   /attach <file>     - Attach a file for context
 *   /attachments       - List currently attached files
 *   /clear-attachments - Remove all attached files
 *   /pause             - Show pause instructions
 *   /resume            - Show resume instructions
 *   /clear             - Clear the workspace for this repo
 *   /models            - Show configured and available models
 *   /focus <module>    - Focus queries on a specific IntelliJ module
 *   /modules           - List detected IntelliJ modules
 *   /repos             - List registered repositories
 *   /quit              - Exit the shell
 *   /help              - Show available commands
 *
 * @param orchestrator   the pipeline orchestrator that executes user queries
 * @param console        the rich console for formatted output
 * @param fileProcessor  the file processor for handling attachments
 * @param ollamaClient   the Ollama HTTP client for model queries
 * @param whisperTranscriber optional transcriber for voice input
 * @param config         the Forge configuration
 */
class ReplShell(
    private val orchestrator: Orchestrator,
    private val console: ForgeConsole,
    private val fileProcessor: FileProcessor,
    private val ollamaClient: OllamaClient,
    private val whisperTranscriber: WhisperTranscriber?,
    private val config: ForgeConfig
) {
    private val attachedFiles = mutableListOf<Path>()
    private val reader = BufferedReader(InputStreamReader(System.`in`))
    private var focusModule: String? = null
    private val workspaceManager = WorkspaceManager(config)

    /**
     * Starts the interactive REPL loop for the given [repoPath].
     * Blocks until the user enters /quit or the input stream is closed.
     *
     * @param repoPath the path to the repository to operate on
     */
    fun run(repoPath: Path) {
        console.banner()
        console.info("Repository: $repoPath")
        console.info("Forge REPL -- Type your question or command. Type /help for commands.")
        console.println("")

        while (true) {
            if (focusModule != null) {
                console.promptWithModule(focusModule!!)
            } else {
                console.prompt()
            }

            val line = try {
                reader.readLine()
            } catch (_: Exception) {
                null
            }

            if (line == null) {
                console.info("Input closed. Exiting.")
                break
            }

            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            if (trimmed.startsWith("/")) {
                val shouldContinue = handleCommand(trimmed, repoPath)
                if (!shouldContinue) break
            } else {
                executeQuery(trimmed, repoPath)
            }
        }
    }

    /**
     * Handles a slash command. Returns false if the REPL should exit.
     */
    private fun handleCommand(command: String, repoPath: Path): Boolean {
        val parts = command.split("\\s+".toRegex(), limit = 2)
        val cmd = parts[0].lowercase()
        val arg = parts.getOrNull(1)?.trim() ?: ""

        when (cmd) {
            "/quit", "/exit", "/q" -> {
                console.info("Goodbye!")
                return false
            }

            "/help", "/h" -> {
                printHelp()
            }

            "/voice", "/v" -> {
                handleVoice(repoPath)
            }

            "/attach" -> {
                attachFile(arg)
            }

            "/attachments" -> {
                showAttachments()
            }

            "/clear-attachments" -> {
                attachedFiles.clear()
                console.success("All attachments cleared.")
            }

            "/pause" -> {
                console.info("Use Ctrl+C during pipeline execution to pause.")
                console.info("Press Ctrl+C once to pause, twice to stop.")
            }

            "/resume" -> {
                console.info("Resume is handled automatically when the pipeline is paused.")
            }

            "/clear" -> {
                handleClear(repoPath)
            }

            "/models" -> {
                showModels()
            }

            "/focus" -> {
                handleFocus(arg, repoPath)
            }

            "/modules" -> {
                handleModules(repoPath)
            }

            "/repos" -> {
                handleRepos(repoPath)
            }

            else -> {
                console.warn("Unknown command: $cmd. Type /help for available commands.")
            }
        }

        return true
    }

    /**
     * Executes a natural-language query through the orchestrator pipeline.
     * Prints the execution trace, task metadata, and the LLM response.
     * Attachments are consumed (cleared) after each query execution.
     */
    private fun executeQuery(input: String, repoPath: Path) {
        try {
            val result = runBlocking {
                orchestrator.execute(input, repoPath, attachedFiles.toList(), focusModule)
            }

            // Print trace
            console.printTrace(result.trace.map { Triple(it.stage, it.detail, it.durationMs) })

            // Print result
            console.println("")
            console.info("Task: ${result.taskType.displayName} | Model: ${result.model}")
            console.result(result.response)

            // Clear attachments after use
            if (attachedFiles.isNotEmpty()) {
                attachedFiles.clear()
                console.info("Attachments cleared after query.")
            }
        } catch (e: StoppedException) {
            console.warn("Pipeline stopped by user.")
        } catch (e: Exception) {
            console.error("Execution failed: ${e.message}")
        }
    }

    // ── Command handlers ─────────────────────────────────────────────────────

    /**
     * Records audio from the microphone, transcribes it with Whisper,
     * and executes the transcribed text as a query.
     */
    private fun handleVoice(repoPath: Path) {
        if (whisperTranscriber == null) {
            console.error("Whisper transcriber not available.")
            if (!isModelDownloaded(config.voice)) {
                console.error("Whisper model not downloaded. Run 'forge voice-setup' first.")
            }
            return
        }

        if (!isMicrophoneAvailable()) {
            console.error("No microphone detected. Check your audio settings.")
            return
        }

        console.info("Recording... (speak now, silence will stop recording)")
        val audioData = try {
            recordUntilSilence(
                maxDurationSec = config.voice.maxRecordingSec,
                silenceThresholdDb = config.voice.silenceThresholdDb,
                silenceDurationMs = config.voice.silenceDurationMs
            )
        } catch (e: Exception) {
            console.error("Recording failed: ${e.message}")
            return
        }

        console.info("Transcribing...")
        val transcriptionResult = try {
            whisperTranscriber.transcribe(audioData, config.voice.language)
        } catch (e: Exception) {
            console.error("Transcription failed: ${e.message}")
            return
        }

        val text = transcriptionResult.text.trim()
        if (text.isEmpty()) {
            console.warn("No speech detected in recording.")
            return
        }

        console.info("Transcribed: $text")
        console.println("")
        executeQuery(text, repoPath)
    }

    /**
     * Attaches a file to the current session. The file will be included as
     * additional context in the next query execution.
     */
    private fun attachFile(pathStr: String) {
        if (pathStr.isEmpty()) {
            console.warn("Usage: /attach <file-path>")
            return
        }

        val path = Paths.get(pathStr).let {
            if (it.isAbsolute) it else Paths.get(System.getProperty("user.dir")).resolve(it)
        }

        if (!Files.exists(path)) {
            console.error("File not found: $path")
            return
        }

        if (!Files.isRegularFile(path)) {
            console.error("Not a regular file: $path")
            return
        }

        attachedFiles.add(path)
        console.success("Attached: ${path.fileName} (${attachedFiles.size} file(s) total)")
    }

    /**
     * Lists all currently attached files with their sizes.
     */
    private fun showAttachments() {
        if (attachedFiles.isEmpty()) {
            console.info("No files attached.")
            return
        }

        console.info("Attached files:")
        for ((index, file) in attachedFiles.withIndex()) {
            val size = try {
                Files.size(file)
            } catch (_: Exception) {
                -1L
            }
            val sizeStr = if (size >= 0) "${size / 1024}KB" else "unknown size"
            console.println("  ${index + 1}. ${file.fileName} ($sizeStr)")
        }
    }

    /**
     * Clears the workspace for the current repository so that the next
     * query will re-scan from scratch.
     */
    private fun handleClear(repoPath: Path) {
        console.info("Clearing workspace for $repoPath...")
        try {
            val wsManager = WorkspaceManager(config)
            wsManager.clear(repoPath)
            console.success("Workspace cleared. Next query will re-scan the repository.")
        } catch (e: Exception) {
            console.error("Failed to clear workspace: ${e.message}")
        }
    }

    /**
     * Shows the configured model role assignments and queries Ollama for
     * the list of locally available models.
     */
    private fun showModels() {
        val configuredModels = listOf(
            "Classify" to config.models.classify,
            "Reason" to config.models.reason,
            "Code" to config.models.code,
            "Embed" to config.models.embed,
            "Summarize" to config.models.summarize,
            "Vision" to config.models.vision
        )
        console.modelInfo(configuredModels)

        console.info("Checking Ollama for available models...")
        try {
            val available = runBlocking { ollamaClient.listModels() }
            if (available.isEmpty()) {
                console.warn("No models found in Ollama. Is Ollama running?")
            } else {
                console.println("")
                console.info("Available in Ollama (${available.size} models):")
                for (model in available) {
                    val sizeStr = if (model.size > 0) " (${model.size / (1024 * 1024)}MB)" else ""
                    val familyStr = if (model.family.isNotBlank()) " [${model.family}]" else ""
                    console.println("  - ${model.name}$sizeStr$familyStr")
                }
            }
        } catch (e: Exception) {
            console.error("Failed to connect to Ollama: ${e.message}")
        }
    }

    /**
     * Handles the /focus command: sets or clears the focused IntelliJ module.
     */
    private fun handleFocus(arg: String, repoPath: Path) {
        if (arg.isBlank()) {
            if (focusModule != null) {
                focusModule = null
                console.success("Focus cleared. Searching all modules.")
            } else {
                console.info("Usage: /focus <module-name> or /focus (to clear)")
            }
        } else {
            try {
                val workspace = workspaceManager.getOrCreate(repoPath)
                val module = workspace.db.getModuleByName(arg)
                if (module != null) {
                    focusModule = arg
                    console.success("Focused on module: $arg (${module.fileCount} files, type: ${module.moduleType})")
                } else {
                    console.error("Module not found: $arg")
                }
            } catch (e: Exception) {
                console.error("Failed to look up module: ${e.message}")
            }
        }
    }

    /**
     * Handles the /modules command: lists all detected IntelliJ modules.
     */
    private fun handleModules(repoPath: Path) {
        try {
            val workspace = workspaceManager.getOrCreate(repoPath)
            val modules = workspace.db.getAllModules()
            if (modules.isEmpty()) {
                console.warn("No modules detected. Run 'forge modules <path>' first.")
            } else {
                console.info("IntelliJ Modules: ${modules.size}")
                for (m in modules.take(30)) {
                    console.println("  ${m.name} [${m.moduleType ?: "?"}] ${m.fileCount} files")
                }
                if (modules.size > 30) console.println("  ... and ${modules.size - 30} more")
            }
        } catch (e: Exception) {
            console.error("Failed to list modules: ${e.message}")
        }
    }

    /**
     * Handles the /repos command: lists all registered repositories.
     */
    private fun handleRepos(repoPath: Path) {
        try {
            val workspace = workspaceManager.getOrCreate(repoPath)
            val repos = workspace.db.getAllRepos()
            if (repos.isEmpty()) {
                console.warn("No repos registered.")
            } else {
                console.info("Repositories: ${repos.size}")
                for (r in repos) {
                    val marker = if (r.isPrimary) " [PRIMARY]" else ""
                    console.println("  ${r.name}$marker — ${r.localPath}")
                }
            }
        } catch (e: Exception) {
            console.error("Failed to list repos: ${e.message}")
        }
    }

    /**
     * Prints the help text listing all available REPL slash commands.
     */
    private fun printHelp() {
        console.println("")
        console.println("Available commands:")
        console.println("  /voice             Record audio and transcribe with Whisper")
        console.println("  /attach <file>     Attach a file for additional context")
        console.println("  /attachments       List currently attached files")
        console.println("  /clear-attachments Remove all attached files")
        console.println("  /clear             Clear workspace (re-scan on next query)")
        console.println("  /models            Show configured and available models")
        console.println("  /focus <module>    Focus queries on a specific IntelliJ module")
        console.println("  /modules           List detected IntelliJ modules")
        console.println("  /repos             List registered repositories")
        console.println("  /help              Show this help message")
        console.println("  /quit              Exit the shell")
        console.println("")
        console.println("Or just type a question or instruction to analyze the repository.")
        console.println("")
    }
}
