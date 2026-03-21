package forge.web

import forge.ForgeConfig
import forge.core.ModelRole
import forge.core.Orchestrator
import forge.evolution.DatasetBuilder
import forge.evolution.LocalModelRegistry
import forge.evolution.ModelEvolutionPlanner
import forge.index.IndexQueryService
import forge.index.ProjectIndexCoordinator
import forge.llm.ModelSelector
import forge.llm.OllamaClient
import forge.workspace.WorkspaceManager
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/**
 * Installs all FORGE REST API routes on the given [Routing] scope.
 *
 * Every endpoint calls Kotlin services directly — no subprocess execution,
 * no shell commands, no SQL string interpolation. This eliminates the
 * command injection and SQL injection vulnerabilities from the Node.js server.
 */
fun Routing.apiRoutes(
    webState: WebState,
    config: ForgeConfig,
    ollamaClient: OllamaClient,
    workspaceManager: WorkspaceManager,
    modelSelector: ModelSelector,
    orchestrator: Orchestrator
) {
    route("/api") {

        // ── Current path ─────────────────────────────────────────────────

        get("/current-path") {
            val repo = webState.repoPath ?: ""
            val dbPath = if (repo.isNotEmpty()) webState.computeDbPath(repo) else ""
            call.respond(CurrentPathResponse(
                repoPath = repo,
                dbPath = dbPath,
                hasDb = webState.hasDatabase
            ))
        }

        // ── Stats ────────────────────────────────────────────────────────

        get("/stats") {
            val db = webState.database
            val repo = webState.repoPath ?: ""

            if (db == null) {
                call.respond(StatsResponse(repoPath = repo))
                return@get
            }

            val stats = withContext(Dispatchers.IO) {
                val files = db.getFileCount()
                val modules = db.getModuleCount()
                val moduleTypes = db.getDistinctModuleTypeCount()
                val chunks = db.getChunkCount()
                val embeddings = db.getEmbeddingCount()
                val embeddingPct = if (chunks > 0) (embeddings * 100 / chunks) else 0

                val ollamaModels = try {
                    ollamaClient.listModels().size
                } catch (_: Exception) { 0 }

                StatsResponse(
                    files = files,
                    modules = modules,
                    moduleTypes = "$moduleTypes types",
                    chunks = chunks,
                    embeddings = embeddings,
                    embeddingPct = embeddingPct,
                    ollamaModels = ollamaModels,
                    repoPath = repo
                )
            }
            call.respond(stats)
        }

        // ── Workspaces ───────────────────────────────────────────────────

        get("/workspaces") {
            val workspaces = withContext(Dispatchers.IO) {
                workspaceManager.listWorkspaces().map { ws ->
                    WorkspaceResponse(
                        repoPath = ws.repoPath,
                        hash = ws.path.fileName.toString(),
                        createdAt = ws.createdAt,
                        hasDb = Files.exists(ws.path.resolve("workspace.db")),
                        isCurrent = ws.repoPath == webState.repoPath
                    )
                }
            }
            call.respond(workspaces)
        }

        // ── Browse directories ───────────────────────────────────────────

        get("/browse") {
            if (!Security.browseLimiter.tryAcquire("browse")) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("Rate limit exceeded"))
                return@get
            }

            val dir = call.request.queryParameters["dir"]
            val homePath = System.getProperty("user.home")
            val targetDir = dir ?: homePath

            val result = withContext(Dispatchers.IO) {
                val resolved = Path.of(targetDir).toAbsolutePath().normalize()

                // Security: validate browse path
                val pathError = Security.validateBrowsePath(resolved)
                if (pathError != null) {
                    BrowseResponse(
                        current = resolved.toString(),
                        parent = resolved.parent?.toString() ?: resolved.toString(),
                        error = pathError
                    )
                } else {
                    try {
                        val entries = Files.newDirectoryStream(resolved).use { stream ->
                            stream.filter { Files.isDirectory(it) && !it.fileName.toString().startsWith(".") }
                                .map { DirEntry(name = it.fileName.toString(), path = it.toString()) }
                                .sortedBy { it.name.lowercase() }
                        }
                        val isGitRepo = Files.exists(resolved.resolve(".git"))
                        BrowseResponse(
                            current = resolved.toString(),
                            parent = resolved.parent?.toString() ?: resolved.toString(),
                            dirs = entries,
                            isGitRepo = isGitRepo
                        )
                    } catch (e: Exception) {
                        BrowseResponse(
                            current = resolved.toString(),
                            parent = resolved.parent?.toString() ?: resolved.toString(),
                            error = e.message
                        )
                    }
                }
            }
            call.respond(result)
        }

        // ── Set path ─────────────────────────────────────────────────────

        post("/set-path") {
            val body = call.receive<SetPathRequest>()
            if (body.path.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing path"))
                return@post
            }

            val resolved = Path.of(body.path).toAbsolutePath().normalize()
            if (!Files.exists(resolved)) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Path does not exist: $resolved"))
                return@post
            }

            if (!webState.setRepoPath(resolved.toString())) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid path: $resolved"))
                return@post
            }

            call.respond(CurrentPathResponse(
                repoPath = webState.repoPath ?: "",
                dbPath = webState.computeDbPath(resolved.toString()),
                hasDb = webState.hasDatabase
            ))
        }

        // ── Status ───────────────────────────────────────────────────────

        get("/status") {
            val status = withContext(Dispatchers.IO) {
                val ollamaAvailable = try { ollamaClient.isAvailable() } catch (_: Exception) { false }
                val workspaceCount = try { workspaceManager.listWorkspaces().size } catch (_: Exception) { 0 }
                val models = try {
                    val available = ollamaClient.listModels()
                    val configuredModels = setOf(
                        config.models.classify, config.models.reason, config.models.code,
                        config.models.embed, config.models.summarize, config.models.vision
                    )
                    available.map { m ->
                        val role = when {
                            m.name.startsWith(config.models.classify) -> "classify"
                            m.name.startsWith(config.models.reason) -> "reason"
                            m.name.startsWith(config.models.code) -> "code"
                            m.name.startsWith(config.models.embed) -> "embed"
                            m.name.startsWith(config.models.summarize) -> "summarize"
                            m.name.startsWith(config.models.vision) -> "vision"
                            else -> null
                        }
                        ModelInfoResponse(
                            name = m.name,
                            size = m.size,
                            parameterSize = m.parameterSize,
                            family = m.family,
                            quantizationLevel = m.quantizationLevel,
                            isConfigured = configuredModels.any { c -> m.name.startsWith(c) },
                            role = role
                        )
                    }
                } catch (_: Exception) { emptyList() }

                val sb = StringBuilder()
                sb.appendLine("Ollama: ${if (ollamaAvailable) "connected" else "not available"} (${config.ollama.host})")
                sb.appendLine("Workspaces: $workspaceCount")
                sb.appendLine("Models: ${models.size} available")
                for (m in models) {
                    val tag = if (m.isConfigured) " [configured: ${m.role}]" else ""
                    sb.appendLine("  - ${m.name} (${m.parameterSize})$tag")
                }

                StatusResponse(
                    output = sb.toString(),
                    ollamaAvailable = ollamaAvailable,
                    workspaces = workspaceCount,
                    models = models
                )
            }
            call.respond(status)
        }

        // ── Models ───────────────────────────────────────────────────────

        get("/models") {
            val models = withContext(Dispatchers.IO) {
                try {
                    ollamaClient.listModels().map { m ->
                        ModelInfoResponse(
                            name = m.name,
                            size = m.size,
                            parameterSize = m.parameterSize,
                            family = m.family,
                            quantizationLevel = m.quantizationLevel
                        )
                    }
                } catch (_: Exception) { emptyList() }
            }
            call.respond(models)
        }

        // ── Analyze ──────────────────────────────────────────────────────

        post("/analyze") {
            if (!Security.analyzeLimiter.tryAcquire("analyze")) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("Rate limit exceeded. Analyze is resource-intensive."))
                return@post
            }

            val repo = webState.repoPath
            if (repo.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("No repository selected"))
                return@post
            }

            val result = withContext(Dispatchers.IO) {
                orchestrator.execute(
                    userInput = "Analyze this repository. Provide an overview of its architecture, tech stack, and key components.",
                    repoPath = Path.of(repo)
                )
            }

            // Refresh DB after analyze (it creates the workspace)
            webState.refreshDatabase()

            call.respond(ForgeResponse(
                trace = result.trace.map { TraceEntry(it.stage, it.detail, it.durationMs) },
                response = result.response,
                taskType = result.taskType.displayName,
                model = result.model
            ))
        }

        // ── Focus ────────────────────────────────────────────────────────

        post("/focus") {
            val body = call.receive<FocusRequest>()
            val repo = webState.repoPath
            if (repo.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("No repository selected"))
                return@post
            }

            val result = withContext(Dispatchers.IO) {
                orchestrator.execute(
                    userInput = body.query,
                    repoPath = Path.of(repo),
                    focusModule = body.module,
                    forceReanalyze = body.forceReanalyze
                )
            }

            call.respond(ForgeResponse(
                trace = result.trace.map { TraceEntry(it.stage, it.detail, it.durationMs) },
                response = result.response,
                taskType = result.taskType.displayName,
                model = result.model
            ))
        }

        // ── Ask (general question) ───────────────────────────────────────

        post("/ask") {
            if (!Security.askLimiter.tryAcquire("ask")) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("Rate limit exceeded"))
                return@post
            }

            val body = call.receive<AskRequest>()

            // Validate input
            val queryError = Security.validateQuery(body.query)
            if (queryError != null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(queryError))
                return@post
            }
            if (body.module != null) {
                val moduleError = Security.validateModuleName(body.module)
                if (moduleError != null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(moduleError))
                    return@post
                }
            }

            val repo = webState.repoPath
            if (repo.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("No repository selected"))
                return@post
            }

            val result = withContext(Dispatchers.IO) {
                orchestrator.execute(
                    userInput = body.query,
                    repoPath = Path.of(repo),
                    focusModule = body.module
                )
            }

            call.respond(ForgeResponse(
                trace = result.trace.map { TraceEntry(it.stage, it.detail, it.durationMs) },
                response = result.response,
                taskType = result.taskType.displayName,
                model = result.model
            ))
        }

        // ── Ask with SSE streaming trace ─────────────────────────────────

        post("/ask/stream") {
            if (!Security.askLimiter.tryAcquire("ask-stream")) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("Rate limit exceeded"))
                return@post
            }

            val body = call.receive<AskRequest>()
            val queryError = Security.validateQuery(body.query)
            if (queryError != null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(queryError))
                return@post
            }

            val repo = webState.repoPath
            if (repo.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("No repository selected"))
                return@post
            }

            val traceChannel = Channel<TraceEvent>(Channel.BUFFERED)

            // Launch execution in background coroutine
            val scope = CoroutineScope(Dispatchers.IO)
            val job = scope.launch {
                try {
                    orchestrator.executeWithTrace(
                        userInput = body.query,
                        repoPath = Path.of(repo),
                        traceChannel = traceChannel,
                        focusModule = body.module,
                        forceReanalyze = body.forceReanalyze
                    )
                } catch (e: Exception) {
                    try {
                        traceChannel.send(TraceEvent.error("PIPELINE", e.message ?: "Unknown error"))
                        traceChannel.send(TraceEvent.done("Error: ${e.message}", "error", "unknown", 0))
                    } catch (_: Exception) { }
                } finally {
                    traceChannel.close()
                }
            }

            // Stream events to client as SSE
            call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                for (event in traceChannel) {
                    write(event.toSSE())
                    flush()
                }
                job.join()
            }
        }

        // ── Config ───────────────────────────────────────────────────────

        get("/config") {
            // Return the current config as a simplified JSON representation
            call.respond(mapOf(
                "ollama_host" to config.ollama.host,
                "ollama_timeout" to config.ollama.timeoutSeconds,
                "model_classify" to config.models.classify,
                "model_reason" to config.models.reason,
                "model_code" to config.models.code,
                "model_embed" to config.models.embed,
                "model_summarize" to config.models.summarize,
                "model_vision" to config.models.vision,
                "workspace_base_dir" to config.workspace.baseDir,
                "chunk_max_lines" to config.workspace.chunkMaxLines,
                "max_context_chunks" to config.retrieval.maxContextChunks,
                "similarity_threshold" to config.retrieval.similarityThreshold,
                "decomposition_enabled" to config.decomposition.enabled,
                "max_partitions" to config.decomposition.maxPartitions,
                "intellij_enabled" to config.intellij.enabled,
                "evolution_enabled" to config.evolution.enabled,
                "evolution_collect_data" to config.evolution.collectTrainingData,
                "evolution_quality_threshold" to config.evolution.qualityThreshold,
                "evolution_product_model" to (config.evolution.productModel ?: "")
            ))
        }

        // ── Model override (runtime LLM switching) ─────────────────

        get("/config/models/overrides") {
            val overrides = modelSelector.getOverrides().mapKeys { it.key.name }
            val defaults = mapOf(
                "CLASSIFY" to config.models.classify,
                "REASON" to config.models.reason,
                "CODE" to config.models.code,
                "EMBED" to config.models.embed,
                "SUMMARIZE" to config.models.summarize,
                "VISION" to config.models.vision
            )
            call.respond(ModelOverridesResponse(overrides = overrides, defaults = defaults))
        }

        post("/config/models") {
            val body = call.receive<ModelOverrideRequest>()
            val role = try {
                ModelRole.valueOf(body.role.uppercase())
            } catch (_: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid role: ${body.role}. Valid: ${ModelRole.entries.joinToString()}"))
                return@post
            }

            if (body.model.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Model name cannot be empty"))
                return@post
            }

            modelSelector.setOverride(role, body.model)
            call.respond(SuccessResponse("Model override set: ${role.name} → ${body.model}"))
        }

        delete("/config/models/{role}") {
            val roleName = call.parameters["role"] ?: ""
            val role = try {
                ModelRole.valueOf(roleName.uppercase())
            } catch (_: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid role: $roleName"))
                return@delete
            }
            modelSelector.clearOverride(role)
            call.respond(SuccessResponse("Override cleared for ${role.name}"))
        }

        delete("/config/models") {
            modelSelector.clearAllOverrides()
            call.respond(SuccessResponse("All model overrides cleared"))
        }

        // ── Evolution endpoints ─────────────────────────────────────

        route("/evolution") {

            // ── Evolution status overview ────────────────────────────
            get("/status") {
                val db = webState.database
                if (db == null) {
                    call.respond(EvolutionStatusResponse(
                        evolutionEnabled = config.evolution.enabled
                    ))
                    return@get
                }

                val status = withContext(Dispatchers.IO) {
                    val planner = ModelEvolutionPlanner()
                    val assessment = planner.assess(db)
                    EvolutionStatusResponse(
                        totalExamples = assessment.totalExamples,
                        validatedExamples = assessment.validatedExamples,
                        averageQuality = assessment.averageQuality,
                        readiness = assessment.readiness.name,
                        nextMilestone = assessment.nextMilestone,
                        recommendation = assessment.recommendation,
                        activeModel = assessment.activeModel,
                        registeredModels = assessment.registeredModels,
                        evolutionEnabled = config.evolution.enabled
                    )
                }
                call.respond(status)
            }

            // ── Paginated training data list ─────────────────────────
            get("/training-data") {
                val db = webState.database
                if (db == null) {
                    call.respond(TrainingDataListResponse(
                        data = emptyList(), total = 0, page = 0, pageSize = 20
                    ))
                    return@get
                }

                val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 0
                val pageSize = (call.request.queryParameters["pageSize"]?.toIntOrNull() ?: 20).coerceIn(1, 100)

                val result = withContext(Dispatchers.IO) {
                    val total = db.getTrainingDataCount()
                    val records = db.getTrainingDataPaginated(page, pageSize)
                    val entries = records.map { r ->
                        TrainingDataEntry(
                            id = r["id"] as? Int ?: 0,
                            input = (r["input"] as? String ?: "").take(500),
                            output = (r["output"] as? String ?: "").take(500),
                            taskType = r["task_type"] as? String,
                            modelUsed = r["model_used"] as? String,
                            quality = r["quality"] as? Double ?: 0.0,
                            userRating = r["user_rating"] as? Int,
                            isValidated = r["is_validated"] as? Boolean ?: false,
                            createdAt = r["created_at"] as? String
                        )
                    }
                    TrainingDataListResponse(
                        data = entries,
                        total = total,
                        page = page,
                        pageSize = pageSize
                    )
                }
                call.respond(result)
            }

            // ── Rate a training data entry ───────────────────────────
            post("/rate") {
                val body = call.receive<RateRequest>()
                val db = webState.database
                if (db == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("No database available"))
                    return@post
                }

                val id = body.taskId.toIntOrNull()
                if (id == null || body.rating !in 1..3) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid id or rating (must be 1-3)"))
                    return@post
                }

                withContext(Dispatchers.IO) {
                    db.updateTrainingDataRating(id, body.rating)
                }
                call.respond(SuccessResponse("Rating saved"))
            }

            // ── Build and export training dataset ────────────────────
            post("/build-dataset") {
                val db = webState.database
                if (db == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("No database available"))
                    return@post
                }

                val body = call.receive<BuildDatasetRequest>()
                val result = withContext(Dispatchers.IO) {
                    val builder = DatasetBuilder(config)
                    val path = builder.buildDataset(db, body.minQuality, body.version)
                    if (path != null) {
                        DatasetExportResponse(
                            success = true,
                            filePath = path.toString(),
                            message = "Dataset exported to $path"
                        )
                    } else {
                        DatasetExportResponse(
                            success = false,
                            message = "No validated training data available for export"
                        )
                    }
                }
                call.respond(result)
            }

            // ── Model registry: list all models ──────────────────────
            get("/models") {
                val db = webState.database
                if (db == null) {
                    call.respond(emptyList<ModelRegistryEntry>())
                    return@get
                }

                val models = withContext(Dispatchers.IO) {
                    db.getModelRegistryEntries().map { r ->
                        ModelRegistryEntry(
                            name = r["name"] as? String ?: "",
                            baseModel = r["base_model"] as? String ?: "",
                            version = r["version"] as? String ?: "",
                            status = r["status"] as? String ?: "draft",
                            createdAt = r["created_at"] as? String,
                            activatedAt = r["activated_at"] as? String
                        )
                    }
                }
                call.respond(models)
            }

            // ── Register a new model ─────────────────────────────────
            post("/register") {
                val db = webState.database
                if (db == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("No database available"))
                    return@post
                }

                val body = call.receive<RegisterModelRequest>()
                if (body.name.isBlank() || body.baseModel.isBlank() || body.version.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("name, baseModel, and version are required"))
                    return@post
                }

                val result = withContext(Dispatchers.IO) {
                    val registry = LocalModelRegistry(config, ollamaClient)
                    registry.register(db, body.name, body.baseModel, body.version, body.modelfilePath)
                }
                call.respond(SuccessResponse("Model '${body.name}' registered (id=$result)"))
            }

            // ── Activate a model ─────────────────────────────────────
            post("/activate") {
                val db = webState.database
                if (db == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("No database available"))
                    return@post
                }

                val body = call.receive<ActivateModelRequest>()
                val registry = LocalModelRegistry(config, ollamaClient)
                val result = registry.activate(db, body.modelName)

                if (result.success) {
                    call.respond(SuccessResponse(result.message))
                } else {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(result.message))
                }
            }

            // ── Rollback to previous model ───────────────────────────
            post("/rollback") {
                val db = webState.database
                if (db == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("No database available"))
                    return@post
                }

                val registry = LocalModelRegistry(config, ollamaClient)
                val result = registry.rollback(db)

                if (result.success) {
                    call.respond(SuccessResponse(result.message))
                } else {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(result.message))
                }
            }
        }

        // ── Index endpoints ──────────────────────────────────────

        route("/index") {

            get("/status") {
                val db = webState.database
                if (db == null) {
                    call.respond(IndexStatusResponse())
                    return@get
                }
                val result = withContext(Dispatchers.IO) {
                    val queryService = IndexQueryService(db)
                    val stats = queryService.getIndexStats()
                    IndexStatusResponse(
                        indexed = stats.totalEntities > 0,
                        lastIndexedAt = stats.lastIndexedAt,
                        indexStatus = stats.indexStatus,
                        totalEntities = stats.totalEntities,
                        totalRelationships = stats.totalRelationships,
                        totalLinesIndexed = stats.totalLinesIndexed,
                        totalDependencyEdges = stats.totalDependencyEdges,
                        entitiesByType = stats.entitiesByType,
                        relationshipsByType = stats.relationshipsByType,
                        filesByClassification = stats.filesByClassification
                    )
                }
                call.respond(result)
            }

            get("/entities") {
                val db = webState.database
                if (db == null) {
                    call.respond(emptyList<EntityResponse>())
                    return@get
                }
                val query = call.request.queryParameters["q"] ?: ""
                val type = call.request.queryParameters["type"]
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                val result = withContext(Dispatchers.IO) {
                    val entities = if (query.isNotEmpty()) {
                        db.searchEntities(query, limit)
                    } else if (type != null) {
                        db.getEntitiesByType(type, limit)
                    } else {
                        db.searchEntities("", limit)
                    }
                    entities.map { e ->
                        val file = db.getFileById(e.fileId)
                        EntityResponse(
                            id = e.id, name = e.name,
                            qualifiedName = e.qualifiedName, entityType = e.entityType,
                            filePath = file?.relativePath, startLine = e.startLine,
                            endLine = e.endLine, visibility = e.visibility,
                            language = e.language, signature = e.signature
                        )
                    }
                }
                call.respond(result)
            }

            get("/entities/{id}") {
                val db = webState.database
                if (db == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("No workspace"))
                    return@get
                }
                val entityId = call.parameters["id"]?.toIntOrNull()
                if (entityId == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid entity ID"))
                    return@get
                }
                val result = withContext(Dispatchers.IO) {
                    val entity = db.getEntityById(entityId)
                    if (entity == null) return@withContext null
                    val file = db.getFileById(entity.fileId)
                    val outgoing = db.getRelationshipsBySource(entityId)
                    val incoming = db.getRelationshipsByTarget(entityId)
                    EntityDetailResponse(
                        entity = EntityResponse(
                            id = entity.id, name = entity.name,
                            qualifiedName = entity.qualifiedName, entityType = entity.entityType,
                            filePath = file?.relativePath, startLine = entity.startLine,
                            endLine = entity.endLine, visibility = entity.visibility,
                            language = entity.language, signature = entity.signature
                        ),
                        outgoing = outgoing.map { r ->
                            RelationshipResponse(r.sourceEntityId, "", r.targetEntityId,
                                r.targetName, r.relationship, r.confidence, r.sourceLine)
                        },
                        incoming = incoming.map { r ->
                            RelationshipResponse(r.sourceEntityId, "", r.targetEntityId,
                                r.targetName, r.relationship, r.confidence, r.sourceLine)
                        }
                    )
                }
                if (result == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Entity not found"))
                } else {
                    call.respond(result)
                }
            }

            get("/classifications") {
                val db = webState.database
                if (db == null) {
                    call.respond(mapOf("byClass" to emptyMap<String, Int>()))
                    return@get
                }
                val result = withContext(Dispatchers.IO) {
                    db.getClassificationOverview()
                }
                call.respond(mapOf("byClass" to result))
            }

            post("/rebuild") {
                val db = webState.database
                val repoPath = webState.repoPath
                if (db == null || repoPath == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("No workspace set"))
                    return@post
                }
                CoroutineScope(Dispatchers.IO).launch {
                    val coordinator = ProjectIndexCoordinator(db, java.nio.file.Path.of(repoPath), config)
                    coordinator.buildFullIndex { phase, detail ->
                        println("[INDEX] $phase: $detail")
                    }
                }
                call.respond(SuccessResponse("Index rebuild started"))
            }
        }
    }
}
