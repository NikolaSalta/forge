package forge

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import forge.core.Orchestrator
import forge.core.StateManager
import forge.files.FileProcessor
import forge.intellij.IntelliJModuleResolver
import forge.llm.ModelSelector
import forge.llm.OllamaClient
import forge.llm.PromptBuilder
import forge.ui.ForgeConsole
import forge.ui.ReplShell
import forge.voice.WhisperTranscriber
import forge.voice.isModelDownloaded
import forge.voice.recordUntilSilence
import forge.workspace.ModuleRecord
import forge.web.ForgeServer
import forge.workspace.MultiRepoManager
import forge.workspace.WorkspaceManager
import kotlinx.coroutines.runBlocking
import java.awt.Desktop
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

// ═══════════════════════════════════════════════════════════════════════════════
//  Root command
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Root Forge command. Loads configuration and provides shared services
 * to all subcommands via the Clikt context.
 */
class ForgeCommand : CliktCommand(name = "forge") {
    override fun help(context: com.github.ajalt.clikt.core.Context): String =
        "Forge -- Local AI Code Intelligence. Analyze, review, and generate code using local LLMs."

    private val configPath by option("--config", "-c", help = "Path to forge.yaml config file")
        .path(mustExist = true)

    override fun run() {
        val config = ForgeConfig.load(configPath)
        currentContext.findOrSetObject { config }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  Helper: create shared services from config
// ═══════════════════════════════════════════════════════════════════════════════

private data class ForgeServices(
    val config: ForgeConfig,
    val ollamaClient: OllamaClient,
    val workspaceManager: WorkspaceManager,
    val modelSelector: ModelSelector,
    val promptBuilder: PromptBuilder,
    val fileProcessor: FileProcessor,
    val stateManager: StateManager,
    val orchestrator: Orchestrator,
    val console: ForgeConsole
)

private fun buildServices(config: ForgeConfig): ForgeServices {
    val ollamaClient = OllamaClient(config)
    val workspaceManager = WorkspaceManager(config)
    val modelSelector = ModelSelector(config, ollamaClient)
    val promptBuilder = PromptBuilder()
    val fileProcessor = FileProcessor(ollamaClient, config.models.vision)
    val stateManager = StateManager()
    val console = ForgeConsole(showTrace = config.ui.showTrace)

    val orchestrator = Orchestrator(
        config = config,
        ollamaClient = ollamaClient,
        workspaceManager = workspaceManager,
        modelSelector = modelSelector,
        promptBuilder = promptBuilder,
        fileProcessor = fileProcessor,
        stateManager = stateManager,
        console = console
    )

    return ForgeServices(
        config = config,
        ollamaClient = ollamaClient,
        workspaceManager = workspaceManager,
        modelSelector = modelSelector,
        promptBuilder = promptBuilder,
        fileProcessor = fileProcessor,
        stateManager = stateManager,
        orchestrator = orchestrator,
        console = console
    )
}

/**
 * Helper to retrieve the ForgeConfig from the Clikt context in subcommands.
 */
private fun CliktCommand.getConfig(): ForgeConfig {
    return try {
        currentContext.findObject<ForgeConfig>() ?: ForgeConfig.load()
    } catch (_: Exception) {
        ForgeConfig.load()
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  analyze command
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Performs a full repository analysis on the specified path.
 *
 * Usage: forge analyze <path>
 */
class AnalyzeCommand : CliktCommand(name = "analyze") {
    override fun help(context: com.github.ajalt.clikt.core.Context): String =
        "Perform a full repository analysis."

    private val repoPath by argument("path", help = "Path to the repository")
        .path(mustExist = true)

    override fun run() {
        val config = getConfig()
        val services = buildServices(config)

        val resolvedPath = repoPath.toAbsolutePath().normalize()
        if (!Files.isDirectory(resolvedPath)) {
            services.console.error("Not a directory: $resolvedPath")
            return
        }

        services.console.banner()
        services.console.info("Analyzing repository: $resolvedPath")
        services.console.info("")

        try {
            val result = runBlocking {
                services.orchestrator.execute(
                    userInput = "Perform a comprehensive analysis of this repository",
                    repoPath = resolvedPath
                )
            }

            services.console.printTrace(result.trace.map { Triple(it.stage, it.detail, it.durationMs) })
            services.console.info("")
            services.console.info("Task: ${result.taskType.displayName} | Model: ${result.model}")
            services.console.result(result.response)
            services.console.success("Analysis complete.")
        } catch (e: Exception) {
            services.console.error("Analysis failed: ${e.message}")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  ask command
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Asks a question about a repository.
 *
 * Usage: forge ask <path> "question" [--voice] [--file <file>]
 */
class AskCommand : CliktCommand(name = "ask") {
    override fun help(context: com.github.ajalt.clikt.core.Context): String =
        "Ask a question about a repository."

    private val repoPath by argument("path", help = "Path to the repository")
        .path(mustExist = true)

    private val question by argument("question", help = "The question to ask")
        .optional()

    private val useVoice by option("--voice", "-v", help = "Use voice input instead of text")
        .flag(default = false)

    private val attachFiles by option("--file", "-f", help = "Attach a file for context")
        .path()
        .multiple()

    override fun run() {
        val config = getConfig()
        val services = buildServices(config)

        val resolvedPath = repoPath.toAbsolutePath().normalize()
        if (!Files.isDirectory(resolvedPath)) {
            services.console.error("Not a directory: $resolvedPath")
            return
        }

        // Determine the input text
        val inputText: String = if (useVoice) {
            getVoiceInput(config, services.console) ?: return
        } else {
            if (question == null) {
                services.console.error("No question provided. Use --voice for voice input or provide a question argument.")
                return
            }
            question!!
        }

        services.console.info("Repository: $resolvedPath")
        services.console.info("Question: $inputText")
        services.console.info("")

        val filePaths = attachFiles.map { it.toAbsolutePath().normalize() }

        try {
            val result = runBlocking {
                services.orchestrator.executeDecomposed(
                    userInput = inputText,
                    repoPath = resolvedPath,
                    attachedFiles = filePaths
                )
            }

            services.console.printTrace(result.trace.map { Triple(it.stage, it.detail, it.durationMs) })
            services.console.info("")
            services.console.info("Task: ${result.taskType.displayName} | Model: ${result.model}")
            services.console.result(result.response)
        } catch (e: Exception) {
            services.console.error("Query failed: ${e.message}")
        }
    }

    private fun getVoiceInput(config: ForgeConfig, console: ForgeConsole): String? {
        if (!isModelDownloaded(config.voice)) {
            console.error("Whisper model not downloaded. Run 'forge voice-setup' first.")
            return null
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
            return null
        }

        console.info("Transcribing...")
        val transcriber = WhisperTranscriber(config.voice)
        return try {
            transcriber.init()
            val result = transcriber.transcribe(audioData, config.voice.language)
            transcriber.close()

            val text = result.text.trim()
            if (text.isEmpty()) {
                console.warn("No speech detected.")
                null
            } else {
                console.info("Transcribed: $text")
                text
            }
        } catch (e: Exception) {
            console.error("Transcription failed: ${e.message}")
            transcriber.close()
            null
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  shell command
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Launches an interactive REPL shell for a repository.
 *
 * Usage: forge shell <path>
 */
class ShellCommand : CliktCommand(name = "shell") {
    override fun help(context: com.github.ajalt.clikt.core.Context): String =
        "Launch an interactive REPL shell for a repository."

    private val repoPath by argument("path", help = "Path to the repository")
        .path(mustExist = true)

    override fun run() {
        val config = getConfig()
        val services = buildServices(config)

        val resolvedPath = repoPath.toAbsolutePath().normalize()
        if (!Files.isDirectory(resolvedPath)) {
            services.console.error("Not a directory: $resolvedPath")
            return
        }

        // Set up optional voice components
        val whisperTranscriber: WhisperTranscriber? = if (isModelDownloaded(config.voice)) {
            try {
                val transcriber = WhisperTranscriber(config.voice)
                transcriber.init()
                transcriber
            } catch (e: Exception) {
                services.console.warn("Whisper initialization failed: ${e.message}")
                services.console.warn("Voice input will be unavailable.")
                null
            }
        } else {
            null
        }

        val repl = ReplShell(
            orchestrator = services.orchestrator,
            console = services.console,
            fileProcessor = services.fileProcessor,
            ollamaClient = services.ollamaClient,
            whisperTranscriber = whisperTranscriber,
            config = config
        )

        try {
            repl.run(resolvedPath)
        } finally {
            whisperTranscriber?.close()
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  models command
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Lists Ollama models and their role assignments.
 *
 * Usage: forge models
 */
class ModelsCommand : CliktCommand(name = "models") {
    override fun help(context: com.github.ajalt.clikt.core.Context): String =
        "List configured and available Ollama models."

    override fun run() {
        val config = getConfig()
        val services = buildServices(config)

        // Show configured model roles
        val configuredModels = listOf(
            "Classify" to config.models.classify,
            "Reason" to config.models.reason,
            "Code" to config.models.code,
            "Embed" to config.models.embed,
            "Summarize" to config.models.summarize,
            "Vision" to config.models.vision
        )
        services.console.modelInfo(configuredModels)

        // Show available models from Ollama
        services.console.info("Checking Ollama for available models...")
        try {
            val available = runBlocking { services.ollamaClient.listModels() }
            if (available.isEmpty()) {
                services.console.warn("No models found in Ollama. Is Ollama running?")
            } else {
                services.console.info("")
                services.console.info("Available in Ollama (${available.size} models):")
                for (model in available) {
                    val sizeStr = if (model.size > 0) " (${model.size / (1024 * 1024)}MB)" else ""
                    val familyStr = if (model.family.isNotBlank()) " [${model.family}]" else ""
                    services.console.info("  - ${model.name}$sizeStr$familyStr")
                }
            }
        } catch (e: Exception) {
            services.console.error("Failed to connect to Ollama: ${e.message}")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  status command
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Shows the status of Forge: Ollama connectivity, workspaces, and configuration.
 *
 * Usage: forge status
 */
class StatusCommand : CliktCommand(name = "status") {
    override fun help(context: com.github.ajalt.clikt.core.Context): String =
        "Show Forge status: Ollama connectivity, workspaces, and configuration."

    override fun run() {
        val config = getConfig()
        val services = buildServices(config)

        services.console.info("Forge Status")
        services.console.info("============")
        services.console.info("")

        // Check Ollama
        services.console.info("Ollama:")
        val ollamaAvailable = runBlocking {
            try {
                services.ollamaClient.isAvailable()
            } catch (_: Exception) {
                false
            }
        }
        if (ollamaAvailable) {
            services.console.success("  Connected to ${config.ollama.host}")
            val modelCount = runBlocking {
                try {
                    services.ollamaClient.listModels().size
                } catch (_: Exception) {
                    0
                }
            }
            services.console.info("  Models available: $modelCount")
        } else {
            services.console.error("  Cannot connect to Ollama at ${config.ollama.host}")
        }

        // Show workspaces
        services.console.info("")
        services.console.info("Workspaces:")
        try {
            val workspaces = services.workspaceManager.listWorkspaces()
            if (workspaces.isEmpty()) {
                services.console.info("  No workspaces found.")
            } else {
                for (ws in workspaces) {
                    services.console.info("  - ${ws.repoPath} (${ws.fileCount} files, created ${ws.createdAt})")
                }
            }
        } catch (e: Exception) {
            services.console.error("  Failed to list workspaces: ${e.message}")
        }

        // Show voice status
        services.console.info("")
        services.console.info("Voice:")
        if (isModelDownloaded(config.voice)) {
            services.console.success("  Whisper model '${config.voice.whisperModel}' is available.")
        } else {
            services.console.warn("  Whisper model not downloaded. Run 'forge voice-setup'.")
        }

        // Config summary
        services.console.info("")
        services.console.info("Configuration:")
        services.console.info("  Workspace dir: ${config.resolvedWorkspaceDir()}")
        services.console.info("  Max file size: ${config.workspace.maxFileSizeKb}KB")
        services.console.info("  Chunk size: ${config.workspace.chunkMaxLines} lines")
        services.console.info("  Max context chunks: ${config.retrieval.maxContextChunks}")
        services.console.info("  Similarity threshold: ${config.retrieval.similarityThreshold}")
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  clear command
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Clears the workspace for a repository, or all workspaces if no path given.
 *
 * Usage: forge clear [path]
 */
class ClearCommand : CliktCommand(name = "clear") {
    override fun help(context: com.github.ajalt.clikt.core.Context): String =
        "Clear the workspace for a repository."

    private val repoPath by argument("path", help = "Path to the repository (optional)")
        .path()
        .optional()

    override fun run() {
        val config = getConfig()
        val services = buildServices(config)

        if (repoPath != null) {
            val resolvedPath = repoPath!!.toAbsolutePath().normalize()
            services.console.info("Clearing workspace for: $resolvedPath")
            try {
                services.workspaceManager.clear(resolvedPath)
                services.console.success("Workspace cleared.")
            } catch (e: Exception) {
                services.console.error("Failed to clear workspace: ${e.message}")
            }
        } else {
            services.console.info("Clearing all workspaces...")
            try {
                val workspaces = services.workspaceManager.listWorkspaces()
                if (workspaces.isEmpty()) {
                    services.console.info("No workspaces to clear.")
                    return
                }
                for (ws in workspaces) {
                    val wsRepoPath = Paths.get(ws.repoPath)
                    services.workspaceManager.clear(wsRepoPath)
                    services.console.info("  Cleared: ${ws.repoPath}")
                }
                services.console.success("All workspaces cleared (${workspaces.size} total).")
            } catch (e: Exception) {
                services.console.error("Failed to clear workspaces: ${e.message}")
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  voice-setup command
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Downloads the Whisper model for voice input.
 *
 * Usage: forge voice-setup [--model <model>]
 */
class VoiceSetupCommand : CliktCommand(name = "voice-setup") {
    override fun help(context: com.github.ajalt.clikt.core.Context): String =
        "Download the Whisper model for voice input."

    private val modelName by option("--model", "-m", help = "Whisper model to download: tiny, base, small, medium")
        .default("base")

    override fun run() {
        val config = getConfig()
        val console = ForgeConsole(showTrace = config.ui.showTrace)

        console.info("Setting up Whisper voice input...")
        console.info("Model: $modelName")
        console.info("")

        val transcriber = WhisperTranscriber(config.voice)
        val success = transcriber.ensureModel(modelName)

        if (success) {
            console.success("Whisper model '$modelName' is ready.")
            console.info("You can now use voice input with 'forge ask --voice' or '/voice' in the shell.")
        } else {
            console.error("Failed to set up Whisper model '$modelName'.")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  modules command
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Lists detected IntelliJ modules for a repository.
 *
 * Usage: forge modules <path>
 */
class IntelliJModulesCommand : CliktCommand(name = "modules") {
    override fun help(context: com.github.ajalt.clikt.core.Context): String =
        "List detected IntelliJ modules."

    private val repoPath by argument("path", help = "Path to the repository")
        .path(mustExist = true)

    private val filter by option("--filter", "-f", help = "Filter modules by name substring")
    private val type by option("--type", "-t", help = "Filter modules by type (PLATFORM_API, PLATFORM_IMPL, PLUGIN, etc.)")

    override fun run() {
        val config = getConfig()
        val console = ForgeConsole(showTrace = config.ui.showTrace)
        val wsManager = WorkspaceManager(config)
        val workspace = wsManager.getOrCreate(repoPath.toAbsolutePath().normalize())

        var modules = workspace.db.getAllModules()
        if (modules.isEmpty()) {
            // Discover modules first
            val resolver = IntelliJModuleResolver(repoPath.toAbsolutePath().normalize())
            val repoName = repoPath.fileName.toString()
            val repoId = workspace.db.insertRepo(repoName, repoPath.toAbsolutePath().toString(), null, null, true)
            val discovered = resolver.discoverModules()
            resolver.persistToDatabase(discovered, repoId, workspace.db)
            modules = workspace.db.getAllModules()
        }

        // Apply filters
        var filtered = modules
        filter?.let { f ->
            filtered = filtered.filter { it.name.contains(f, ignoreCase = true) }
        }
        type?.let { t ->
            filtered = filtered.filter { (it.moduleType ?: "").equals(t, ignoreCase = true) }
        }

        printModulesSummary(console, modules)
        console.println("")
        printModulesTable(console, filtered)
    }

    private fun printModulesSummary(console: ForgeConsole, modules: List<ModuleRecord>) {
        val byType = modules.groupBy { it.moduleType ?: "unknown" }
        console.info("IntelliJ Modules: ${modules.size} total")
        for ((typeName, mods) in byType.entries.sortedByDescending { it.value.size }) {
            console.println("  $typeName: ${mods.size}")
        }
    }

    private fun printModulesTable(console: ForgeConsole, modules: List<ModuleRecord>) {
        if (modules.isEmpty()) {
            console.warn("No modules match the filter.")
            return
        }
        console.println(String.format("%-50s %-15s %8s %s", "MODULE", "TYPE", "FILES", "DEPENDENCIES"))
        console.println("-".repeat(110))
        for (module in modules) {
            val deps = module.dependencies?.take(50) ?: ""
            console.println(String.format("%-50s %-15s %8d %s",
                module.name.take(49),
                module.moduleType ?: "unknown",
                module.fileCount,
                deps))
        }
        console.println("")
        console.println("Showing ${modules.size} modules. Use --filter/-f or --type/-t to narrow results.")
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  connect command
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Connects a satellite repository to an existing workspace.
 *
 * Usage: forge connect <repo> <path>
 */
class ConnectCommand : CliktCommand(name = "connect") {
    override fun help(context: com.github.ajalt.clikt.core.Context): String =
        "Connect a satellite repository."

    private val repoSpec by argument("repo", help = "Repository (owner/repo or URL)")
    private val repoPath by argument("path", help = "Path to the primary repository")
        .path(mustExist = true)

    override fun run() {
        val config = getConfig()
        val console = ForgeConsole(showTrace = config.ui.showTrace)
        val wsManager = WorkspaceManager(config)
        val workspace = wsManager.getOrCreate(repoPath.toAbsolutePath().normalize())
        val multiRepo = MultiRepoManager(config, wsManager)

        val url = if (repoSpec.startsWith("http")) repoSpec else "https://github.com/$repoSpec.git"
        val name = repoSpec.substringAfterLast('/').removeSuffix(".git")

        val result = multiRepo.connectRepo(workspace, name, url)
        if (result != null) {
            console.success("Connected: ${result.name} at ${result.localPath}")
        } else {
            console.error("Failed to connect repository: $repoSpec")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  focus command
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Queries within a specific IntelliJ module.
 *
 * Usage: forge focus <module> <path> <question>
 */
class FocusCommand : CliktCommand(name = "focus") {
    override fun help(context: com.github.ajalt.clikt.core.Context): String =
        "Query within a specific IntelliJ module."

    private val moduleName by argument("module", help = "Name of the IntelliJ module")
    private val repoPath by argument("path", help = "Path to the repository")
        .path(mustExist = true)
    private val question by argument("question", help = "The question to ask")

    override fun run() {
        val config = getConfig().let { c ->
            c.copy(intellij = c.intellij.copy(enabled = true))
        }
        val services = buildServices(config)

        services.console.banner()
        services.console.info("Module: $moduleName")
        services.console.info("Repository: ${repoPath.toAbsolutePath().normalize()}")
        services.console.info("Question: $question")
        services.console.info("")

        try {
            val result = runBlocking {
                services.orchestrator.executeDecomposed(
                    question,
                    repoPath.toAbsolutePath().normalize(),
                    focusModule = moduleName
                )
            }

            services.console.printTrace(result.trace.map { Triple(it.stage, it.detail, it.durationMs) })
            services.console.info("")
            services.console.info("Task: ${result.taskType.displayName} | Model: ${result.model}")
            services.console.result(result.response)
        } catch (e: Exception) {
            services.console.error("Query failed: ${e.message}")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  serve command — embedded web server
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Starts the FORGE embedded web server (Ktor/Netty).
 * Serves both the REST API and the web UI on localhost.
 * Replaces the previous Node.js server.js — no subprocess execution needed.
 */
class ServeCommand : CliktCommand(name = "serve") {
    override fun help(context: com.github.ajalt.clikt.core.Context): String =
        "Start the FORGE web dashboard server."

    private val port by option("--port", "-p", help = "Port to listen on")
        .default("3456")

    private val noOpen by option("--no-open", help = "Don't open browser automatically")
        .flag(default = false)

    override fun run() {
        val config = getConfig()
        val services = buildServices(config)

        val portNum = port.toIntOrNull() ?: 3456

        val server = ForgeServer(
            config = config,
            ollamaClient = services.ollamaClient,
            workspaceManager = services.workspaceManager,
            modelSelector = services.modelSelector,
            orchestrator = services.orchestrator,
            port = portNum
        )

        // Install shutdown hook
        Runtime.getRuntime().addShutdownHook(Thread {
            println("\n  Shutting down FORGE server...")
            server.stop()
        })

        server.start(wait = false)

        // Open browser unless --no-open
        if (!noOpen) {
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(URI("http://localhost:$portNum"))
                }
            } catch (_: Exception) {
                // Browser opening is best-effort
            }
        }

        // Block main thread until shutdown
        server.waitForShutdown()
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  Entry point
// ═══════════════════════════════════════════════════════════════════════════════

fun main(args: Array<String>) {
    // Desktop mode: if launched with no args, start the web server
    if (args.isEmpty()) {
        ForgeCommand()
            .subcommands(ServeCommand())
            .main(arrayOf("serve"))
        return
    }

    // CLI mode: normal Clikt command routing
    ForgeCommand()
        .subcommands(
            AnalyzeCommand(),
            AskCommand(),
            ShellCommand(),
            ModelsCommand(),
            StatusCommand(),
            ClearCommand(),
            VoiceSetupCommand(),
            IntelliJModulesCommand(),
            ConnectCommand(),
            FocusCommand(),
            ServeCommand()
        )
        .main(args)
}
