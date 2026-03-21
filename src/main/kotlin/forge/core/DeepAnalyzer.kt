package forge.core

import forge.ForgeConfig
import forge.llm.ChatMessage
import forge.llm.ModelSelector
import forge.llm.OllamaClient
import forge.llm.ResponseParser
import forge.web.TraceEvent
import forge.workspace.Database
import forge.workspace.FileRecord
import kotlinx.coroutines.channels.SendChannel
import java.nio.file.Files
import java.nio.file.Path

/**
 * Multi-pass deep repository analyzer.
 *
 * Instead of making one LLM call with a fixed token budget, DeepAnalyzer:
 * 1. Discovers all modules/services in the repository
 * 2. Analyzes each module individually via a dedicated LLM call
 * 3. Writes intermediate per-module results to disk (cache)
 * 4. Synthesizes all module analyses into one comprehensive report
 *
 * This trades speed for depth: a 17-service monorepo gets 17+ LLM calls
 * instead of 1, producing class-level, method-level, and dependency-level detail.
 */
class DeepAnalyzer(
    private val config: ForgeConfig,
    private val ollama: OllamaClient,
    private val modelSelector: ModelSelector
) {

    companion object {
        /** Max characters of source code to send per module analysis call. */
        private const val MODULE_FILE_BUDGET_CHARS = 512_000_000

        /** Max files to read per module (prioritize key files). */
        private const val MAX_FILES_PER_MODULE = 500_000

        /** Max characters of module summaries to send for synthesis. */
        private const val SYNTHESIS_BUDGET_CHARS = 512_000_000
    }

    // ── Data classes ─────────────────────────────────────────────────────────

    data class ModuleAnalysis(
        val modulePath: String,
        val analysis: String,
        val fileCount: Int,
        val filesAnalyzed: Int,
        val durationMs: Long
    )

    data class DeepAnalysisResult(
        val moduleAnalyses: List<ModuleAnalysis>,
        val synthesis: String,
        val totalModules: Int,
        val totalFilesAnalyzed: Int,
        val totalDurationMs: Long
    )

    // ── Main entry point ─────────────────────────────────────────────────────

    /**
     * Perform deep analysis of the entire repository.
     *
     * @param db             Workspace database with scanned files.
     * @param repoPath       Root path of the repository.
     * @param evidence       Collected evidence map (category:key -> value).
     * @param workspacePath  Path to the workspace directory for caching.
     * @param traceChannel   Optional SSE channel for progress events.
     * @return Combined deep analysis result with per-module details and synthesis.
     */
    suspend fun analyzeDeep(
        db: Database,
        repoPath: Path,
        evidence: Map<String, String>,
        workspacePath: Path,
        traceChannel: SendChannel<TraceEvent>?,
        clearCache: Boolean = false
    ): DeepAnalysisResult {
        val overallStart = System.currentTimeMillis()

        // Clear cached analysis if requested
        if (clearCache) {
            val analysisDir = workspacePath.resolve("analysis")
            if (Files.exists(analysisDir)) {
                Files.list(analysisDir).use { stream ->
                    stream.filter { it.toString().endsWith(".md") }
                        .forEach { Files.deleteIfExists(it) }
                }
            }
        }

        // 1. Discover modules from evidence + file structure
        val modules = discoverModules(db, evidence, repoPath)

        if (modules.isEmpty()) {
            // Fallback: treat entire repo as one module
            val singleModule = analyzeModule(".", db, repoPath, workspacePath, 1, 1, traceChannel)
            val synthesis = singleModule.analysis
            return DeepAnalysisResult(
                moduleAnalyses = listOf(singleModule),
                synthesis = synthesis,
                totalModules = 1,
                totalFilesAnalyzed = singleModule.filesAnalyzed,
                totalDurationMs = System.currentTimeMillis() - overallStart
            )
        }

        // 2. Analyze each module with progress
        val totalModules = modules.size
        val analyses = mutableListOf<ModuleAnalysis>()

        for ((idx, modulePath) in modules.withIndex()) {
            traceChannel?.send(TraceEvent.analysisProgress(
                current = idx + 1,
                total = totalModules,
                moduleName = modulePath,
                percent = ((idx.toFloat() / totalModules) * 100).toInt()
            ))

            val analysis = analyzeModule(
                modulePath = modulePath,
                db = db,
                repoPath = repoPath,
                workspacePath = workspacePath,
                current = idx + 1,
                total = totalModules,
                traceChannel = traceChannel
            )
            analyses.add(analysis)
        }

        // Mark all modules complete
        traceChannel?.send(TraceEvent.analysisProgress(
            current = totalModules,
            total = totalModules,
            moduleName = "All modules analyzed",
            percent = 100
        ))

        // 3. Synthesize all module analyses into final report
        traceChannel?.send(TraceEvent.stageStarted(
            "SYNTHESIS", totalModules + 3, totalModules + 4,
            "Synthesizing ${analyses.size} module analyses into final report..."
        ))

        val synthesisStart = System.currentTimeMillis()
        val synthesis = synthesize(analyses, evidence, traceChannel)
        val synthesisDuration = System.currentTimeMillis() - synthesisStart

        traceChannel?.send(TraceEvent.stageCompleted(
            "SYNTHESIS", synthesisDuration,
            "Synthesized ${analyses.size} modules into comprehensive report"
        ))

        // 4. Write final synthesis to disk
        val analysisDir = workspacePath.resolve("analysis")
        Files.createDirectories(analysisDir)
        Files.writeString(analysisDir.resolve("_SYNTHESIS.md"), synthesis)

        return DeepAnalysisResult(
            moduleAnalyses = analyses,
            synthesis = synthesis,
            totalModules = totalModules,
            totalFilesAnalyzed = analyses.sumOf { it.filesAnalyzed },
            totalDurationMs = System.currentTimeMillis() - overallStart
        )
    }

    // ── Module discovery ─────────────────────────────────────────────────────

    /**
     * Discovers modules/services by combining:
     * - MONOREPO_STRUCTURE evidence (preferred, has sub-service paths)
     * - Top-level directory structure analysis (fallback)
     *
     * Evidence keys use the format "sub_service:microservices/alerts" where
     * the path after the colon is the actual module directory path.
     */
    private fun discoverModules(
        db: Database,
        evidence: Map<String, String>,
        repoPath: Path
    ): List<String> {
        val modules = mutableSetOf<String>()

        for ((key, value) in evidence) {
            if (!key.startsWith("MONOREPO_STRUCTURE:")) continue
            val subKey = key.removePrefix("MONOREPO_STRUCTURE:")

            // NEW format: "sub_service:microservices/alerts"
            if (subKey.startsWith("sub_service:")) {
                val path = subKey.removePrefix("sub_service:")
                if (path.isNotBlank()) modules.add(path)
                continue
            }

            // Skip metadata keys and service_dir container entries
            if (subKey.startsWith("service_dir:") ||
                subKey == "workspace_managers" || subKey == "docker_compose_services" ||
                subKey == "monorepo_summary" || subKey == "top_level_structure" ||
                subKey.startsWith("top_level_")) {
                continue
            }

            // BACKWARD COMPAT: old format "sub_service" with path in value
            if (subKey == "sub_service") {
                val path = value.substringBefore(":").trim()
                if (path.isNotBlank() && path.contains("/")) {
                    modules.add(path)
                }
                continue
            }
        }

        if (modules.isNotEmpty()) return modules.sorted()

        // Fallback: discover from file structure using two-level directory scan
        val allFiles = db.getAllFiles()
        val dirFileCount = mutableMapOf<String, Int>()

        for (file in allFiles) {
            if (file.language == null || file.language == "unknown") continue
            val parts = file.relativePath.split("/")
            if (parts.size >= 2) {
                val topDir = parts[0]
                dirFileCount[topDir] = (dirFileCount[topDir] ?: 0) + 1
                if (parts.size >= 3) {
                    val twoLevel = "${parts[0]}/${parts[1]}"
                    dirFileCount[twoLevel] = (dirFileCount[twoLevel] ?: 0) + 1
                }
            }
        }

        // Prefer two-level paths (e.g. microservices/alerts) over top-level (e.g. microservices)
        val candidates = mutableListOf<String>()
        for ((path, count) in dirFileCount) {
            if (count < 2) continue
            val parts = path.split("/")
            if (parts.size == 2) {
                candidates.add(path)
            } else if (parts.size == 1) {
                // Top-level: only add if no two-level children exist
                val hasChildren = dirFileCount.keys.any {
                    it.startsWith("$path/") && it.split("/").size == 2
                }
                if (!hasChildren) candidates.add(path)
            }
        }

        if (candidates.size <= 1) return emptyList()
        return candidates.sorted()
    }

    // ── Per-module analysis ──────────────────────────────────────────────────

    /**
     * Analyze a single module by reading all its source files and sending them
     * to the LLM for deep analysis.
     */
    private suspend fun analyzeModule(
        modulePath: String,
        db: Database,
        repoPath: Path,
        workspacePath: Path,
        current: Int,
        total: Int,
        traceChannel: SendChannel<TraceEvent>?
    ): ModuleAnalysis {
        val moduleStart = System.currentTimeMillis()

        // Check cache first
        val analysisDir = workspacePath.resolve("analysis")
        Files.createDirectories(analysisDir)
        val sanitizedName = modulePath.replace("/", "_").replace("\\", "_").replace(".", "_root_")
        val cacheFile = analysisDir.resolve("$sanitizedName.md")

        if (Files.exists(cacheFile)) {
            val cached = Files.readString(cacheFile)
            if (cached.isNotBlank() && cached.length > 100) {
                return ModuleAnalysis(
                    modulePath = modulePath,
                    analysis = cached,
                    fileCount = 0,
                    filesAnalyzed = 0,
                    durationMs = System.currentTimeMillis() - moduleStart
                )
            }
        }

        // Get all source files for this module
        val allFiles = db.getAllFiles()
        val moduleFiles = if (modulePath == ".") {
            allFiles
        } else {
            allFiles.filter { it.relativePath.startsWith("$modulePath/") }
        }

        // Read file contents within budget
        val fileContents = readFilesWithBudget(moduleFiles, repoPath)

        // Build deep analysis prompt
        val messages = buildDeepAnalysisPrompt(modulePath, fileContents, moduleFiles.size)

        // Call LLM with streaming
        val model = modelSelector.selectForTask(TaskType.REPO_ANALYSIS)
        val responseBuilder = StringBuilder()

        ollama.chatStream(model, messages).collect { token ->
            responseBuilder.append(token)
            traceChannel?.send(TraceEvent.llmToken(token))
        }

        val rawResponse = responseBuilder.toString()
        val cleaned = ResponseParser.toMarkdown(rawResponse)

        // Write to disk cache
        Files.writeString(cacheFile, cleaned)

        return ModuleAnalysis(
            modulePath = modulePath,
            analysis = cleaned,
            fileCount = moduleFiles.size,
            filesAnalyzed = fileContents.size,
            durationMs = System.currentTimeMillis() - moduleStart
        )
    }

    // ── File reading with budget ─────────────────────────────────────────────

    /**
     * Read source files up to a character budget, prioritizing key files:
     * 1. Build files (build.gradle, pom.xml, package.json, Cargo.toml, etc.)
     * 2. Entry points (main.*, app.*, index.*, server.*)
     * 3. Configuration files
     * 4. Models/entities/schemas
     * 5. Controllers/routes/handlers
     * 6. Services/repositories
     * 7. Everything else
     */
    private fun readFilesWithBudget(
        files: List<FileRecord>,
        repoPath: Path
    ): Map<String, String> {
        val result = linkedMapOf<String, String>()
        var budget = MODULE_FILE_BUDGET_CHARS

        // Sort files by priority
        val prioritized = files
            .filter { (it.sizeBytes ?: 0) < config.workspace.maxFileSizeKb * 1024 }
            .sortedBy { filePriority(it.relativePath) }
            .take(MAX_FILES_PER_MODULE)

        for (file in prioritized) {
            if (budget <= 0) break

            val filePath = repoPath.resolve(file.relativePath)
            if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) continue

            try {
                val content = Files.readString(filePath)
                val trimmed = if (content.length > budget) {
                    content.take(budget) + "\n... [truncated, ${content.length} total chars]"
                } else {
                    content
                }
                result[file.relativePath] = trimmed
                budget -= trimmed.length
            } catch (_: Exception) {
                // Skip files that can't be read (binary, encoding issues)
            }
        }

        return result
    }

    /**
     * Returns a priority score for a file path (lower = higher priority).
     */
    private fun filePriority(path: String): Int {
        val name = path.substringAfterLast("/").lowercase()
        return when {
            // Build files — highest priority
            name in setOf("build.gradle", "build.gradle.kts", "pom.xml", "package.json",
                "cargo.toml", "go.mod", "requirements.txt", "pyproject.toml",
                "gemfile", "makefile", "cmake​lists.txt", "meson.build") -> 0

            // Docker / deployment
            name == "dockerfile" || name.startsWith("dockerfile.") ||
                name == "docker-compose.yml" || name == "docker-compose.yaml" -> 1

            // Entry points
            name.startsWith("main.") || name.startsWith("app.") ||
                name.startsWith("index.") || name.startsWith("server.") ||
                name.startsWith("application.") -> 2

            // Configuration
            name.endsWith(".yaml") || name.endsWith(".yml") ||
                name.endsWith(".toml") || name.endsWith(".properties") ||
                name.endsWith(".env.example") || name.endsWith(".conf") -> 3

            // Models, entities, schemas
            path.contains("/model") || path.contains("/entity") ||
                path.contains("/schema") || path.contains("/migration") ||
                path.contains("/dto") -> 4

            // Controllers, routes, handlers
            path.contains("/controller") || path.contains("/route") ||
                path.contains("/handler") || path.contains("/endpoint") ||
                path.contains("/api/") -> 5

            // Services, repositories
            path.contains("/service") || path.contains("/repository") ||
                path.contains("/repo/") || path.contains("/store/") -> 6

            // Tests (lower priority but still include)
            path.contains("/test") || path.contains("/spec") ||
                name.contains("test") || name.contains("spec") -> 8

            // Everything else
            else -> 7
        }
    }

    // ── Prompt building ──────────────────────────────────────────────────────

    /**
     * Build a deep analysis prompt for a single module.
     */
    private fun buildDeepAnalysisPrompt(
        modulePath: String,
        files: Map<String, String>,
        totalFiles: Int
    ): List<ChatMessage> {
        val system = """You are a senior software architect performing a deep code analysis.
Analyze the provided module with MAXIMUM DEPTH and PRECISION.
Use ACTUAL names from the code — classes, methods, fields, types, endpoints, tables.
Never invent or guess names. If a file is truncated, note what was visible.
Format your response in well-structured markdown."""

        val user = buildString {
            appendLine("# Deep Analysis: Module \"$modulePath\"")
            appendLine("This module contains $totalFiles source files. ${files.size} are shown below.")
            appendLine()

            // List all file contents
            for ((path, content) in files) {
                appendLine("## File: $path")
                appendLine("```")
                appendLine(content)
                appendLine("```")
                appendLine()
            }

            appendLine("""
## Analysis Instructions

Analyze this module thoroughly. For EACH significant file, provide:

### 1. Classes/Interfaces/Structs
- Name, purpose (1 sentence)
- Key fields with types
- Key methods with signatures and what they do
- Inheritance / interface implementations
- Design patterns used (Factory, Repository, Observer, etc.)

### 2. Dependencies
- Internal imports (other modules in this repo)
- External libraries (with purpose of each)
- Dependency injection / wiring

### 3. Database (if any schemas, entities, migrations, ORM models)
- Every table/collection name
- Columns with types
- Relationships (FK, indexes, constraints)
- Migrations (what they change)

### 4. API Endpoints (if any controllers, routes, handlers)
- HTTP method + full path
- Request body / query params with types
- Response types
- Authentication/authorization requirements
- Middleware chain

### 5. Configuration
- Environment variables used
- Config files and their structure
- Feature flags

### 6. Architecture Observations
- How this module fits in the larger system
- Message queues, event buses, pub/sub
- Caching strategies
- Error handling patterns
- Logging patterns

### 7. Risks & Issues
- Security concerns (hardcoded secrets, SQL injection, etc.)
- Missing error handling
- Performance concerns
- Code smells / tech debt

Be EXHAUSTIVE. Use actual class names, method signatures, column names from the code.
""".trimIndent())
        }

        return listOf(
            ChatMessage(role = "system", content = system),
            ChatMessage(role = "user", content = user)
        )
    }

    // ── Synthesis ────────────────────────────────────────────────────────────

    /**
     * Synthesize all per-module analyses into one comprehensive report.
     */
    private suspend fun synthesize(
        moduleAnalyses: List<ModuleAnalysis>,
        evidence: Map<String, String>,
        traceChannel: SendChannel<TraceEvent>?
    ): String {
        val system = """You are a senior software architect creating a comprehensive repository analysis report.
You are given individual deep analyses of each module/service in a repository.
Your job is to SYNTHESIZE these into one unified, well-structured report.
Preserve the depth and specificity from each module analysis.
Add cross-cutting insights: how modules interact, shared patterns, system-level architecture.
Use markdown formatting with clear hierarchy."""

        val user = buildString {
            appendLine("# Repository Synthesis Request")
            appendLine()

            // Include key evidence
            appendLine("## Repository-Level Evidence")
            var evidenceBudget = 32_000_000
            for ((key, value) in evidence) {
                if (evidenceBudget <= 0) break
                val line = "- **$key**: $value"
                appendLine(line)
                evidenceBudget -= line.length
            }
            appendLine()

            // Include module analyses (within budget)
            appendLine("## Per-Module Deep Analyses")
            appendLine()

            var synthBudget = SYNTHESIS_BUDGET_CHARS
            for (ma in moduleAnalyses) {
                if (synthBudget <= 0) {
                    appendLine("*(remaining ${moduleAnalyses.size - moduleAnalyses.indexOf(ma)} modules omitted due to size)*")
                    break
                }
                appendLine("### Module: ${ma.modulePath} (${ma.fileCount} files, ${ma.filesAnalyzed} analyzed)")
                val trimmedAnalysis = if (ma.analysis.length > synthBudget) {
                    ma.analysis.take(synthBudget) + "\n... [truncated]"
                } else {
                    ma.analysis
                }
                appendLine(trimmedAnalysis)
                appendLine()
                synthBudget -= trimmedAnalysis.length
            }

            appendLine("""
## Synthesis Instructions

Create a comprehensive report with these sections:

### A. Executive Summary
- What this repository is (1 paragraph)
- Tech stack (languages, frameworks, databases, infra)
- Scale (total services, files, endpoints, tables)

### B. Architecture Overview
- System architecture pattern (monolith/microservices/modular monolith)
- How services communicate (REST, gRPC, message queues, events)
- Data flow diagram (described textually)
- Shared libraries and cross-cutting concerns

### C. Module-by-Module Breakdown
For EACH module, preserve the depth from individual analyses:
- Purpose and responsibility
- Key classes with methods
- API endpoints (method + path + types)
- Database tables (columns + types + relationships)
- External dependencies

### D. Cross-Cutting Concerns
- Authentication / authorization strategy
- Error handling patterns
- Logging and monitoring
- Configuration management
- Testing strategy

### E. Data Architecture
- All databases and their schemas
- Data flow between services
- Caching layers
- Message queues / event systems

### F. Deployment & Infrastructure
- Docker setup
- CI/CD pipeline
- Environment configuration
- Scaling considerations

### G. Risk Summary
- Security vulnerabilities found
- Performance bottlenecks
- Tech debt and code smells
- Missing tests or documentation

### H. Final Engineering Assessment
- Overall code quality score (1-10)
- Architecture fitness
- Recommendations for improvement (prioritized)
""".trimIndent())
        }

        val messages = listOf(
            ChatMessage(role = "system", content = system),
            ChatMessage(role = "user", content = user)
        )

        val model = modelSelector.selectForTask(TaskType.REPO_ANALYSIS)
        val responseBuilder = StringBuilder()

        // Clear previous streaming output before synthesis
        traceChannel?.send(TraceEvent.llmToken("\n\n---\n\n# 📊 SYNTHESIS: Combined Repository Analysis\n\n"))

        ollama.chatStream(model, messages).collect { token ->
            responseBuilder.append(token)
            traceChannel?.send(TraceEvent.llmToken(token))
        }

        return ResponseParser.toMarkdown(responseBuilder.toString())
    }
}
