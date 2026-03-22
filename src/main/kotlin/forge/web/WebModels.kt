package forge.web

import kotlinx.serialization.Serializable

// ── Request models ───────────────────────────────────────────────────────────

@Serializable
data class SetPathRequest(
    val path: String
)

@Serializable
data class AnalyzeRequest(
    val repoPath: String? = null
)

@Serializable
data class FocusRequest(
    val module: String = "platform/lang-api",
    val query: String = "What are the main abstractions?",
    val forceReanalyze: Boolean = false
)

@Serializable
data class AskRequest(
    val query: String,
    val module: String? = null,
    val forceReanalyze: Boolean = false
)

@Serializable
data class RateRequest(
    val taskId: String,
    val rating: Int // 1=bad, 2=ok, 3=good
)

@Serializable
data class ConfigUpdateRequest(
    val yaml: String
)

@Serializable
data class ModelOverrideRequest(
    val role: String,
    val model: String
)

@Serializable
data class ModelOverridesResponse(
    val overrides: Map<String, String>,
    val defaults: Map<String, String>
)

// ── Response models ──────────────────────────────────────────────────────────
// Field names use camelCase to match the existing frontend JS expectations.

@Serializable
data class StatsResponse(
    val files: Int = 0,
    val modules: Int = 0,
    val moduleTypes: String = "0 types",
    val chunks: Int = 0,
    val embeddings: Int = 0,
    val embeddingPct: Int = 0,
    val ollamaModels: Int = 0,
    val repoPath: String = ""
)

@Serializable
data class CurrentPathResponse(
    val repoPath: String,
    val dbPath: String,
    val hasDb: Boolean
)

@Serializable
data class WorkspaceResponse(
    val repoPath: String,
    val hash: String = "",
    val createdAt: String = "",
    val hasDb: Boolean = false,
    val isCurrent: Boolean = false
)

@Serializable
data class BrowseResponse(
    val current: String,
    val parent: String,
    val dirs: List<DirEntry> = emptyList(),
    val isGitRepo: Boolean = false,
    val error: String? = null
)

@Serializable
data class DirEntry(
    val name: String,
    val path: String
)

@Serializable
data class TraceEntry(
    val stage: String,
    val detail: String = "",
    val durationMs: Long = 0
)

@Serializable
data class ForgeResponse(
    val trace: List<TraceEntry> = emptyList(),
    val response: String = "",
    val taskType: String = "",
    val model: String = "",
    val raw: String = ""
)

@Serializable
data class StatusResponse(
    val output: String = "",
    val ollamaAvailable: Boolean = false,
    val workspaces: Int = 0,
    val models: List<ModelInfoResponse> = emptyList()
)

@Serializable
data class ModelInfoResponse(
    val name: String,
    val size: Long = 0,
    val parameterSize: String = "",
    val family: String = "",
    val quantizationLevel: String = "",
    val isConfigured: Boolean = false,
    val role: String? = null
)

@Serializable
data class ErrorResponse(
    val error: String
)

// ── Plan mode models ────────────────────────────────────────────────────────

@Serializable
data class PlanGenerateRequest(
    val query: String
)

@Serializable
data class PlanRefineRequest(
    val planId: String,
    val feedback: String
)

@Serializable
data class PlanApproveRequest(
    val planId: String
)

@Serializable
data class PlanExecuteRequest(
    val planId: String
)

@Serializable
data class PlanResponse(
    val planId: String,
    val phase: String,
    val plan: String,
    val revisions: Int = 0
)

@Serializable
data class SuccessResponse(
    val message: String
)

// ── Evolution request/response models ───────────────────────────────────────

@Serializable
data class BuildDatasetRequest(
    val minQuality: Double = 0.6,
    val version: String = "v1"
)

@Serializable
data class RegisterModelRequest(
    val name: String,
    val baseModel: String,
    val version: String,
    val modelfilePath: String? = null
)

@Serializable
data class ActivateModelRequest(
    val modelName: String
)

@Serializable
data class EvolutionStatusResponse(
    val totalExamples: Int = 0,
    val validatedExamples: Int = 0,
    val averageQuality: Double = 0.0,
    val readiness: String = "NOT_READY",
    val nextMilestone: String = "",
    val recommendation: String = "",
    val activeModel: String? = null,
    val registeredModels: Int = 0,
    val evolutionEnabled: Boolean = true
)

@Serializable
data class TrainingDataEntry(
    val id: Int,
    val input: String,
    val output: String,
    val taskType: String? = null,
    val modelUsed: String? = null,
    val quality: Double = 0.0,
    val userRating: Int? = null,
    val isValidated: Boolean = false,
    val createdAt: String? = null
)

@Serializable
data class TrainingDataListResponse(
    val data: List<TrainingDataEntry>,
    val total: Int,
    val page: Int,
    val pageSize: Int
)

@Serializable
data class ModelRegistryEntry(
    val name: String,
    val baseModel: String,
    val version: String,
    val status: String,
    val createdAt: String? = null,
    val activatedAt: String? = null
)

@Serializable
data class DatasetExportResponse(
    val success: Boolean,
    val filePath: String? = null,
    val recordCount: Int = 0,
    val message: String = ""
)

// ── Index response models ─────────────────────────────────────────────────

@Serializable
data class IndexStatusResponse(
    val indexed: Boolean = false,
    val lastIndexedAt: String? = null,
    val indexStatus: String = "not_built",
    val totalEntities: Int = 0,
    val totalRelationships: Int = 0,
    val totalLinesIndexed: Int = 0,
    val totalDependencyEdges: Int = 0,
    val entitiesByType: Map<String, Int> = emptyMap(),
    val relationshipsByType: Map<String, Int> = emptyMap(),
    val filesByClassification: Map<String, Int> = emptyMap()
)

@Serializable
data class EntityResponse(
    val id: Int = 0,
    val name: String = "",
    val qualifiedName: String? = null,
    val entityType: String = "",
    val filePath: String? = null,
    val startLine: Int = 0,
    val endLine: Int = 0,
    val visibility: String? = null,
    val language: String? = null,
    val signature: String? = null
)

@Serializable
data class EntityDetailResponse(
    val entity: EntityResponse = EntityResponse(),
    val outgoing: List<RelationshipResponse> = emptyList(),
    val incoming: List<RelationshipResponse> = emptyList()
)

@Serializable
data class RelationshipResponse(
    val sourceId: Int = 0,
    val sourceName: String = "",
    val targetId: Int? = null,
    val targetName: String = "",
    val relationship: String = "",
    val confidence: Double = 1.0,
    val sourceLine: Int? = null
)
