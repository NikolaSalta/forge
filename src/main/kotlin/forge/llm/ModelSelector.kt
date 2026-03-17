package forge.llm

import forge.ForgeConfig
import forge.ModelsConfig
import forge.FallbackModels
import forge.core.ModelRole
import forge.core.TaskType

/**
 * Selects the appropriate Ollama model for a given task type or model role.
 * Checks model availability and falls back to a secondary model when the primary
 * is not pulled locally.
 */
class ModelSelector(
    private val config: ForgeConfig,
    private val client: OllamaClient
) {
    @Volatile
    private var availableModels: Set<String>? = null

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
     */
    suspend fun selectForRole(role: ModelRole): String {
        val primary = primaryModelForRole(role, config.models)
        if (isModelAvailable(primary)) {
            return primary
        }

        val fallback = fallbackModelForRole(role, config.models.fallback)
        if (fallback != null && isModelAvailable(fallback)) {
            return fallback
        }

        // If neither primary nor fallback is available, return the primary name anyway.
        // The caller will get a clear Ollama error when it tries to use it.
        return primary
    }

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
            ModelRole.CLASSIFY  -> models.classify
            ModelRole.CODE      -> models.code
            ModelRole.REASON    -> models.reason
            ModelRole.SUMMARIZE -> models.summarize
            ModelRole.EMBED     -> models.embed
            ModelRole.VISION    -> models.vision
        }
    }

    private fun fallbackModelForRole(role: ModelRole, fallback: FallbackModels): String? {
        return when (role) {
            ModelRole.CLASSIFY  -> fallback.classify
            ModelRole.CODE      -> fallback.code
            ModelRole.REASON    -> fallback.reason
            ModelRole.SUMMARIZE -> fallback.summarize
            // No fallback defined for embed and vision
            ModelRole.EMBED     -> null
            ModelRole.VISION    -> null
        }
    }
}
