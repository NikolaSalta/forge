package forge.core

import forge.ForgeConfig
import forge.llm.ContextAssembler
import forge.llm.ModelSelector
import forge.llm.OllamaClient
import forge.llm.PromptBuilder
import forge.llm.ResponseParser
import forge.index.ProjectIndexCoordinator
import forge.retrieval.GenericModuleDiscovery
import forge.retrieval.Chunker
import forge.retrieval.EvidenceCollector
import forge.retrieval.HierarchicalRetriever
import forge.retrieval.RepoScanner
import forge.retrieval.ScanStage
import forge.workspace.Database
import forge.workspace.EmbeddingStore
import forge.workspace.Workspace
import java.nio.file.Path

// ═══════════════════════════════════════════════════════════════════════════════
//  Pipeline Stage Definitions
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Enumerates the logical stages of the Forge processing pipeline.
 * Used for trace reporting and progress display.
 */
enum class PipelineStageName(val displayName: String) {
    INTENT_RESOLUTION("Intent Resolution"),
    WORKSPACE_INIT("Workspace Init"),
    MODEL_SELECTION("Model Selection"),
    REPO_SCANNING("Repository Scanning"),
    MODULE_DISCOVERY("Module Discovery"),
    CHUNKING("Code Chunking"),
    PROJECT_INDEXING("Project Indexing"),
    EMBEDDING("Embedding Generation"),
    EVIDENCE_COLLECTION("Evidence Collection"),
    CONTEXT_ASSEMBLY("Context Assembly"),
    LLM_INFERENCE("LLM Inference"),
    VALIDATION("Validation"),
    OUTPUT("Output")
}

/**
 * Represents a single stage in the Forge processing pipeline.
 *
 * @property name        short identifier for the stage (e.g. "SCAN", "CHUNK")
 * @property description human-readable description of what this stage does
 * @property execute     the suspend lambda that performs the stage's work
 */
data class PipelineStage(
    val name: String,
    val description: String,
    val execute: suspend (PipelineContext) -> Unit
)

/**
 * Shared mutable context passed through every stage of the pipeline. Stages
 * read from and write to this context so that downstream stages can consume
 * the outputs of upstream stages.
 */
data class PipelineContext(
    val workspace: Workspace,
    val db: Database,
    val repoPath: Path,
    val taskType: TaskType,
    val taskId: String,
    val userInput: String,
    val config: ForgeConfig,
    val stateManager: StateManager,
    val ollama: OllamaClient,
    val modelSelector: ModelSelector,
    val promptBuilder: PromptBuilder,
    var selectedModel: String = "",
    var evidenceMap: Map<String, String> = emptyMap(),
    var missingEvidence: List<String> = emptyList(),
    var contextChunks: List<String> = emptyList(),
    var fileContents: Map<String, String> = emptyMap(),
    var llmResponse: String = "",
    var attachedFileContents: Map<String, String> = emptyMap(),
    var focusModule: String? = null,
    var moduleContext: String = "",
    var relevantModuleNames: List<String> = emptyList(),
    var embeddedChunkCount: Int = -1
)

/**
 * A single entry in the execution trace, recording what happened at each stage.
 */
data class TraceEntry(
    val stage: String,
    val detail: String,
    val durationMs: Long
)

/**
 * The result returned after the Forge pipeline completes execution.
 */
data class ForgeResult(
    val response: String,
    val taskType: TaskType,
    val model: String,
    val trace: List<TraceEntry>
)

// ═══════════════════════════════════════════════════════════════════════════════
//  Pipeline Builder
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Builds an ordered list of [PipelineStage]s appropriate for the given [TaskType].
 *
 * Standard pipeline stages:
 * 1. SCAN               -- scan the repository for files
 * 2. CHUNK              -- split source files into semantic chunks
 * 3. EMBED (optional)   -- generate embeddings for all chunks
 * 4. EVIDENCE           -- collect structural evidence about the repo
 * 5. CONTEXT_ASSEMBLY   -- find similar chunks and assemble context
 * 6. LLM_CALL           -- invoke the LLM with the assembled prompt
 * 7. VALIDATE           -- validate / post-process the LLM response
 *
 * Tasks that require deep analysis or code generation include the EMBED stage;
 * simpler informational tasks skip it.
 */
fun buildPipeline(taskType: TaskType): List<PipelineStage> {
    val stages = mutableListOf<PipelineStage>()

    // ── Stage 1: SCAN ──────────────────────────────────────────────────────
    // Scans the repository for source files if not already scanned.
    stages.add(PipelineStage(
        name = "SCAN",
        description = "Scanning repository for source files"
    ) { ctx ->
        ctx.stateManager.checkPauseOrStop()

        val fileCount = ctx.db.getFileCount()
        if (fileCount == 0) {
            val scanner = RepoScanner(ctx.config.workspace)
            scanner.scan(
                repoPath = ctx.repoPath,
                db = ctx.db,
                stages = ScanStage.entries.toSet(),
                ignorePatterns = ctx.config.retrieval.scanIgnore.toSet()
            )
            ctx.db.setMeta("scanned", "true")
        }
    })

    // ── Stage 1b: MODULE_DISCOVERY (conditional) ────────────────────────────
    // Discovers modules via generic build-file markers (pom.xml, build.gradle, etc.).
    stages.add(PipelineStage(
        name = "MODULE_DISCOVERY",
        description = "Discovering project modules"
    ) { ctx ->
        ctx.stateManager.checkPauseOrStop()

        // Ensure repo record exists
        val repoName = ctx.repoPath.fileName.toString()
        var repoRecord = ctx.db.getRepoByPath(ctx.repoPath.toString())
        val repoId = if (repoRecord != null) repoRecord.id else {
            ctx.db.insertRepo(repoName, ctx.repoPath.toString(), null, null, true)
        }

        val moduleCount = ctx.db.getModuleCount()
        if (moduleCount == 0) {
            // Generic module discovery by build-file markers
            val discovered = GenericModuleDiscovery.discover(ctx.repoPath)
            System.err.println("[MODULE_DISCOVERY] Generic discovery found ${discovered.size} modules in ${ctx.repoPath}")
            for (mod in discovered) {
                System.err.println("[MODULE_DISCOVERY]   → ${mod.name} (${mod.moduleType}) at ${mod.path}")
                ctx.db.insertModule(
                    repoId = repoId,
                    path = mod.path,
                    name = mod.name,
                    pluginXml = null,
                    moduleType = mod.moduleType,
                    dependencies = "language:${mod.language}"
                )
            }
        }

        // Assign module_id to files that don't have one yet
        val unassignedCount = ctx.db.countFilesWithoutModule()
        if (unassignedCount > 0) {
            ctx.db.assignFilesToModules(ctx.repoPath.toString())
        }
    })

    // ── Stage 2: CHUNK ─────────────────────────────────────────────────────
    // Splits source files into semantic chunks for retrieval.
    stages.add(PipelineStage(
        name = "CHUNK",
        description = "Splitting source files into semantic chunks"
    ) { ctx ->
        ctx.stateManager.checkPauseOrStop()

        val chunkCount = ctx.db.getChunkCount()
        if (chunkCount == 0) {
            val chunker = Chunker()
            chunker.chunkAll(
                db = ctx.db,
                repoPath = ctx.repoPath,
                maxChunkLines = ctx.config.workspace.chunkMaxLines,
                overlapLines = ctx.config.workspace.chunkOverlapLines,
                maxChunksPerFile = ctx.config.workspace.maxChunksPerFile
            )
        }
    })

    // ── Stage 2b: PROJECT_INDEXING ───────────────────────────────────────
    // Builds the absolute project index: entities, relationships, line index,
    // dependency graph, and file classifications.
    stages.add(PipelineStage(
        name = "PROJECT_INDEXING",
        description = "Building absolute project index"
    ) { ctx ->
        ctx.stateManager.checkPauseOrStop()

        val coordinator = ProjectIndexCoordinator(ctx.db, ctx.repoPath, ctx.config)
        if (!coordinator.isIndexBuilt()) {
            coordinator.buildFullIndex { phase, detail, _, _ ->
                // Progress is logged but not surfaced to trace yet
            }
        } else if (ctx.config.scale.incrementalScan) {
            val (changed, deleted) = coordinator.detectChangedFiles()
            if (changed.isNotEmpty() || deleted.isNotEmpty()) {
                coordinator.updateIncremental(changed, deleted)
            }
        }
    })

    // ── Stage 3: EMBED (conditional) ───────────────────────────────────────
    // Generates embeddings for all un-embedded chunks. Only included for
    // task types that need semantic search (deep analysis or code generation).
    if (taskType.requiresDeepAnalysis || taskType.generatesCode) {
        stages.add(PipelineStage(
            name = "EMBED",
            description = "Generating embeddings for code chunks"
        ) { ctx ->
            ctx.stateManager.checkPauseOrStop()

            val embeddingStore = EmbeddingStore(ctx.ollama, ctx.db, ctx.config)
            embeddingStore.embedAllChunksAsync(batchSize = ctx.config.retrieval.embeddingBatchSize)
        })
    }

    // ── Stage 4: EVIDENCE ──────────────────────────────────────────────────
    // Collects structural evidence (build system, languages, architecture,
    // etc.) and evaluates the evidence gate.
    stages.add(PipelineStage(
        name = "EVIDENCE",
        description = "Collecting structural evidence about the repository"
    ) { ctx ->
        ctx.stateManager.checkPauseOrStop()

        val collector = EvidenceCollector()
        collector.collect(ctx.taskId, ctx.taskType, ctx.db, ctx.repoPath)

        // Check evidence gate
        val gateResult = collector.checkGate(ctx.taskId, ctx.taskType, ctx.db)
        ctx.missingEvidence = gateResult.missing

        // Build evidence map from collected records
        val records = ctx.db.getEvidenceByTask(ctx.taskId)
        ctx.evidenceMap = records.associate { "${it.category}:${it.key}" to it.value }
    })

    // ── Stage 5: CONTEXT_ASSEMBLY ──────────────────────────────────────────
    // Finds semantically similar chunks via embedding search and merges
    // them with attached file contents.
    stages.add(PipelineStage(
        name = "CONTEXT_ASSEMBLY",
        description = "Assembling context from evidence and similar chunks"
    ) { ctx ->
        ctx.stateManager.checkPauseOrStop()

        // Context assembly via embedding search
        val embeddingStore = EmbeddingStore(ctx.ollama, ctx.db, ctx.config)
        val similarChunks = try {
            embeddingStore.findSimilarAsync(
                query = ctx.userInput,
                topK = ctx.config.retrieval.maxContextChunks,
                threshold = ctx.config.retrieval.similarityThreshold
            )
        } catch (e: Exception) {
            System.err.println("[CONTEXT_ASSEMBLY] Embedding search failed: ${e.message}")
            e.printStackTrace(System.err)
            emptyList()
        }

        ctx.contextChunks = similarChunks.map { scored ->
            scored.chunk.content
        }

        // If modules exist, use hierarchical retrieval for better results
        if (ctx.db.getModuleCount() > 0) {
            val retriever = HierarchicalRetriever(ctx.config, ctx.ollama, ctx.db)
            val result = if (ctx.focusModule != null) {
                val chunks = retriever.searchInModule(
                    ctx.userInput,
                    ctx.focusModule!!,
                    ctx.config.retrieval.maxContextChunks
                )
                forge.retrieval.RetrievalResult(emptyList(), chunks, "focused")
            } else {
                retriever.search(
                    ctx.userInput,
                    chunkTopK = ctx.config.retrieval.maxContextChunks
                )
            }
            ctx.relevantModuleNames = result.relevantModules.map { it.module.name }

            val assembler = ContextAssembler(ctx.config)
            val assembled = assembler.assemble(
                relevantModules = result.relevantModules.map { it.module },
                scoredChunkTexts = result.chunks.map { it.chunk.content },
                evidence = ctx.evidenceMap,
                attachedFiles = ctx.attachedFileContents,
                focusModule = ctx.focusModule
            )
            ctx.moduleContext = assembled.moduleContext
            ctx.contextChunks = assembled.codeChunks
        }

        // Merge attached file contents into the file contents map
        val mergedFiles = ctx.fileContents.toMutableMap()
        mergedFiles.putAll(ctx.attachedFileContents)
        ctx.fileContents = mergedFiles

        // ── Index-based context enrichment ──────────────────────────────────
        // If the absolute index exists, enrich context with structural data.
        // Uses two strategies:
        //   1. Specific queries (contain CamelCase/dotted names) → entity search
        //   2. Broad queries ("main abstractions", "architecture") → structural summary
        val indexCoordinator = ProjectIndexCoordinator(ctx.db, ctx.repoPath, ctx.config)
        if (indexCoordinator.isIndexBuilt()) {
            val queryWords = ctx.userInput
                .split(" ", ",", ".", "?", "!", "(", ")", "\"", "'", "\n", "\t")
                .map { it.trim() }
                .filter { it.length > 2 }
                .distinct()

            // Detect if query mentions specific entity names (CamelCase, dotted, or ALL_CAPS)
            val specificNames = queryWords.filter { word ->
                word.length > 3 && (
                    word[0].isUpperCase() && word.any { it.isLowerCase() } || // CamelCase
                    word.contains(".") || // package.Class
                    word.all { it.isUpperCase() || it == '_' } // CONSTANT_NAME
                )
            }

            val entityContext = if (specificNames.isNotEmpty()) {
                // Strategy 1: Specific entity search by name
                val matchedEntities = specificNames.take(15).flatMap { word ->
                    ctx.db.searchEntities(word, limit = 30)
                }.distinctBy { it.id }.take(100)

                buildString {
                    if (matchedEntities.isNotEmpty()) {
                        appendLine("## Project Index: Matched Entities")
                        appendLine()
                        for (entity in matchedEntities) {
                            val file = ctx.db.getFileById(entity.fileId)
                            val loc = "${file?.relativePath ?: "?"}:${entity.startLine}-${entity.endLine}"
                            appendLine("- ${entity.entityType} `${entity.name}` ($loc)")
                            if (!entity.signature.isNullOrBlank() && entity.signature != entity.name) {
                                appendLine("  signature: ${entity.signature!!.take(120)}")
                            }
                            val outgoing = ctx.db.getRelationshipsBySource(entity.id)
                            for (rel in outgoing.take(8)) {
                                appendLine("  → ${rel.relationship} `${rel.targetName}`")
                            }
                        }
                    }
                }
            } else {
                // Strategy 2: Broad query — inject rich project structural summary
                // For large repos, provide comprehensive package-level overview
                val allClasses = ctx.db.getEntitiesByType("class", limit = 500)
                val allInterfaces = ctx.db.getEntitiesByType("interface", limit = 200)
                val allEnums = ctx.db.getEntitiesByType("enum", limit = 100)

                // Group by top-level module/package for architecture overview
                fun extractModule(entity: forge.workspace.EntityRecord): String {
                    val file = ctx.db.getFileById(entity.fileId)
                    val path = file?.relativePath ?: "unknown"
                    val parts = path.split("/")
                    // Use first 2-3 directory levels as module grouping
                    return when {
                        parts.size >= 3 -> parts.take(3).joinToString("/")
                        parts.size >= 2 -> parts.take(2).joinToString("/")
                        else -> path
                    }
                }

                val classModules = allClasses.groupBy { extractModule(it) }
                val ifaceModules = allInterfaces.groupBy { extractModule(it) }

                val classCount = ctx.db.countEntitiesByType("class")
                val ifaceCount = ctx.db.countEntitiesByType("interface")
                val funcCount = ctx.db.countEntitiesByType("function")
                val enumCount = ctx.db.countEntitiesByType("enum")

                buildString {
                    appendLine("## Project Index: Structural Overview (from absolute index)")
                    appendLine()
                    appendLine("### Scale")
                    appendLine("- Files: ${ctx.db.getFileCount()}")
                    appendLine("- Total entities: ${ctx.db.getEntityCount()}")
                    appendLine("- Classes: $classCount | Interfaces: $ifaceCount | Functions: $funcCount | Enums: $enumCount")
                    appendLine("- Relationships: ${ctx.db.getRelationshipCount()}")
                    appendLine()

                    // Show top modules by class count
                    appendLine("### Module/Package Structure (by class density)")
                    for ((module, classes) in classModules.entries.sortedByDescending { it.value.size }.take(20)) {
                        val ifaces = ifaceModules[module]?.size ?: 0
                        appendLine("**$module/** — ${classes.size} classes, $ifaces interfaces")
                        // Show up to 8 representative classes per module
                        for (cls in classes.sortedByDescending { (it.endLine ?: 0) - (it.startLine ?: 0) }.take(8)) {
                            append("  - `${cls.name}`")
                            if (!cls.signature.isNullOrBlank() && cls.signature != cls.name) {
                                append(" — ${cls.signature!!.take(100)}")
                            }
                            appendLine()
                            // Show extends/implements for each class
                            val rels = ctx.db.getRelationshipsBySource(cls.id)
                                .filter { it.relationship in listOf("extends", "implements") }
                            if (rels.isNotEmpty()) {
                                appendLine("    ${rels.joinToString(", ") { "${it.relationship} `${it.targetName}`" }}")
                            }
                        }
                    }
                    appendLine()

                    // Show key interfaces
                    if (allInterfaces.isNotEmpty()) {
                        appendLine("### Key Interfaces (${allInterfaces.size} of $ifaceCount total)")
                        val ifacesByModule = allInterfaces.groupBy { extractModule(it) }
                        for ((module, ifaces) in ifacesByModule.entries.sortedByDescending { it.value.size }.take(10)) {
                            appendLine("**$module/**")
                            for (iface in ifaces.take(5)) {
                                appendLine("  - `${iface.name}`${if (!iface.signature.isNullOrBlank()) " — ${iface.signature!!.take(80)}" else ""}")
                            }
                        }
                        appendLine()
                    }

                    // Show key enums
                    if (allEnums.isNotEmpty()) {
                        appendLine("### Enums (${allEnums.size} of $enumCount total)")
                        for (en in allEnums.take(30)) {
                            appendLine("- `${en.name}`")
                        }
                    }

                    // Dependency summary
                    val depCount = ctx.db.getDependencyEdgeCount()
                    if (depCount > 0) {
                        appendLine()
                        appendLine("### Module Dependencies ($depCount edges)")
                        // Show a sample of the heaviest dependencies
                    }
                }
            }

            if (entityContext.isNotBlank()) {
                ctx.contextChunks = listOf(entityContext) + ctx.contextChunks
            }
        }
    })

    // ── Stage 6: LLM_CALL ─────────────────────────────────────────────────
    // Selects the appropriate model, builds the full prompt, and invokes
    // the LLM to produce a response.
    stages.add(PipelineStage(
        name = "LLM_CALL",
        description = "Calling LLM with assembled context"
    ) { ctx ->
        ctx.stateManager.checkPauseOrStop()

        // Select model for this task type
        ctx.selectedModel = ctx.modelSelector.selectForTask(ctx.taskType)

        // Build evidence strings for the prompt
        val evidenceStrings = ctx.evidenceMap.map { (key, value) ->
            "[$key] $value"
        }

        // Build the full prompt
        val messages = ctx.promptBuilder.buildTaskPrompt(
            taskType = ctx.taskType,
            evidence = evidenceStrings,
            chunks = ctx.contextChunks,
            fileContents = ctx.fileContents
        )

        // Call the LLM
        ctx.llmResponse = ctx.ollama.chat(ctx.selectedModel, messages)
    })

    // ── Stage 7: VALIDATE ──────────────────────────────────────────────────
    // Post-processes the LLM response: strips thinking tags, validates
    // code blocks for code-generating tasks, and converts to clean markdown.
    stages.add(PipelineStage(
        name = "VALIDATE",
        description = "Validating and cleaning LLM response"
    ) { ctx ->
        ctx.stateManager.checkPauseOrStop()

        // Strip thinking tags from reasoning models and normalize formatting
        ctx.llmResponse = ResponseParser.toMarkdown(ctx.llmResponse)

        // For code-generating tasks, verify that code blocks are present
        if (ctx.taskType.generatesCode) {
            val codeBlocks = ResponseParser.extractCodeBlocks(ctx.llmResponse)
            if (codeBlocks.isEmpty() && ctx.llmResponse.length < 100) {
                ctx.llmResponse = ctx.llmResponse +
                    "\n\n> Note: No code blocks were generated. The response may be incomplete."
            }
        }
    })

    return stages
}
