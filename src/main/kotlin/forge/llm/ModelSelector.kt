package forge.llm

import forge.ForgeConfig
import forge.ModelsConfig
import forge.FallbackModels
import forge.core.ModelRole
import forge.core.TaskType
import java.util.concurrent.ConcurrentHashMap

/**
 * Selects the appropriate Ollama model for a given task type or model role.
 * Checks model availability and falls back to a secondary model when the primary
 * is not pulled locally.
 *
 * Supports runtime overrides: the web UI can temporarily switch a model role
 * to any locally available model without editing YAML or restarting.
 */
class ModelSelector(
    private val config: ForgeConfig,
    private val client: OllamaClient,
    private val agentOrchestrator: AgentOrchestrator? = null
) {
    @Volatile
    private var availableModels: Set<String>? = null

    /** Runtime model overrides set via the web UI. Takes priority over YAML config. */
    private val runtimeOverrides = ConcurrentHashMap<ModelRole, String>()

    /**
     * Select the best available model for the given [TaskType].
     * Uses the task's declared [ModelRole] to look up the configured model name,
     * checks availability, and falls back if necessary.
     */
    suspend fun selectForTask(taskType: TaskType): String {
        return selectForRole(taskType.modelRole)
    }

    /**
     * Select the best available model for the given [ModelRole].
     * Priority: runtime override → YAML config → fallback config.
     */
    suspend fun selectForRole(role: ModelRole): String {
        // 1. Check runtime override first
        val override = runtimeOverrides[role]
        if (override != null && isModelAvailable(override)) {
            return override
        }

        // 2. YAML config primary
        val primary = primaryModelForRole(role, config.models)
        if (isModelAvailable(primary)) {
            return primary
        }

        // 3. YAML config fallback
        val fallback = fallbackModelForRole(role, config.models.fallback)
        if (fallback != null && isModelAvailable(fallback)) {
            return fallback
        }

        // If nothing is available, return override or primary anyway.
        // The caller will get a clear Ollama error when it tries to use it.
        val resolved = override ?: primary

        // Ensure the agent orchestrator has the model warm (best-effort)
        agentOrchestrator?.ensureAgentReady(role, resolved)

        return resolved
    }

    // ── Runtime override API ────────────────────────────────────────────────

    /**
     * Set a runtime model override for a role. Takes effect immediately
     * for the next query — no restart required.
     */
    fun setOverride(role: ModelRole, modelName: String) {
        runtimeOverrides[role] = modelName
    }

    /** Clear the runtime override for a role, reverting to YAML config. */
    fun clearOverride(role: ModelRole) {
        runtimeOverrides.remove(role)
    }

    /** Clear all runtime overrides. */
    fun clearAllOverrides() {
        runtimeOverrides.clear()
    }

    /** Returns the current runtime overrides (role → model name). */
    fun getOverrides(): Map<ModelRole, String> = runtimeOverrides.toMap()

    /**
     * Refresh the cached set of locally available model names.
     * Call this after a model pull or if availability may have changed.
     */
    suspend fun refreshAvailableModels() {
        availableModels = fetchAvailableModelNames()
    }

    /**
     * Returns true if the given model name (or a prefix of it) matches any
     * locally available model.
     */
    suspend fun isModelAvailable(modelName: String): Boolean {
        val available = availableModels ?: fetchAvailableModelNames().also { availableModels = it }
        // Exact match first
        if (modelName in available) return true
        // Ollama allows omitting the ":latest" tag, so also check if any available
        // model starts with the requested name followed by ':'
        return available.any { it.startsWith("$modelName:") || modelName.startsWith("$it:") }
    }

    /**
     * Returns the names of all locally available models.
     */
    suspend fun getAvailableModels(): List<ModelInfo> {
        return try {
            client.listModels()
        } catch (_: OllamaException) {
            emptyList()
        }
    }

    // ── Internal helpers ────────────────────────────────────────────────────────

    private suspend fun fetchAvailableModelNames(): Set<String> {
        return try {
            client.listModels().map { it.name }.toSet()
        } catch (_: OllamaException) {
            emptySet()
        }
    }

    private fun primaryModelForRole(role: ModelRole, models: ModelsConfig): String {
        return when (role) {
            ModelRole.CLASSIFY   -> models.classify
            ModelRole.CODE       -> models.code
            ModelRole.REASON     -> models.reason
            ModelRole.SUMMARIZE  -> models.summarize
            ModelRole.SYNTHESIZE -> models.synthesize
            ModelRole.EMBED      -> models.embed
            ModelRole.VISION     -> models.vision
        }
    }

    private fun fallbackModelForRole(role: ModelRole, fallback: FallbackModels): String? {
        return when (role) {
            ModelRole.CLASSIFY   -> fallback.classify
            ModelRole.CODE       -> fallback.code
            ModelRole.REASON     -> fallback.reason
            ModelRole.SUMMARIZE  -> fallback.summarize
            ModelRole.SYNTHESIZE -> fallback.synthesize
            ModelRole.EMBED      -> null
            ModelRole.VISION     -> null
        }
    }

    /**
     * Returns each model role with its assigned model and loaded status.
     */
    suspend fun getActiveModels(): Map<ModelRole, ActiveModelInfo> {
        val loadedNames = try {
            client.getLoadedModels().map { it.name }.toSet()
        } catch (_: Exception) { emptySet() }

        return ModelRole.entries.associateWith { role ->
            val override = runtimeOverrides[role]
            val configured = primaryModelForRole(role, config.models)
            val modelName = override ?: configured
            ActiveModelInfo(
                modelName = modelName,
                isLoaded = loadedNames.any { it == modelName || it.startsWith("${modelName.split(":").first()}:") },
                isOverride = override != null
            )
        }
    }
}

data class ActiveModelInfo(
    val modelName: String,
    val isLoaded: Boolean,
    val isOverride: Boolean
)
