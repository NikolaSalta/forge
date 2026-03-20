package forge.evolution

import forge.ForgeConfig
import forge.llm.OllamaClient
import forge.workspace.Database

/**
 * Manages the lifecycle of product model versions.
 * Tracks model registration, activation, retirement, and rollback.
 */
class LocalModelRegistry(
    private val config: ForgeConfig,
    private val ollamaClient: OllamaClient
) {
    /**
     * Register a new model version in the local registry.
     */
    fun register(
        db: Database,
        name: String,
        baseModel: String,
        version: String,
        modelfilePath: String? = null
    ): Int {
        return db.insertModelRegistryEntry(
            name = name,
            baseModel = baseModel,
            version = version,
            status = "draft",
            modelfilePath = modelfilePath
        )
    }

    /**
     * Activate a registered model, making it the default for production use.
     * Verifies the model exists in Ollama before activation.
     */
    suspend fun activate(db: Database, modelName: String): ActivationResult {
        // Verify model exists in Ollama
        val available = try {
            ollamaClient.listModels().any { it.name.startsWith(modelName) }
        } catch (_: Exception) { false }

        if (!available) {
            return ActivationResult(
                success = false,
                message = "Model '$modelName' not found in Ollama. Pull it first with: ollama pull $modelName"
            )
        }

        // Run sanity check
        val sanityPassed = try {
            val response = ollamaClient.chat(
                model = modelName,
                messages = listOf(
                    forge.llm.ChatMessage("user", "What is a function in programming? Answer in one sentence.")
                )
            )
            response.length > 10
        } catch (e: Exception) {
            return ActivationResult(
                success = false,
                message = "Model sanity check failed: ${e.message}"
            )
        }

        if (!sanityPassed) {
            return ActivationResult(
                success = false,
                message = "Model '$modelName' produced an invalid response during sanity check"
            )
        }

        // Activate in DB
        db.activateModel(modelName)

        return ActivationResult(
            success = true,
            message = "Model '$modelName' activated successfully"
        )
    }

    /**
     * Roll back to the previous model version.
     */
    fun rollback(db: Database): RollbackResult {
        val restored = db.rollbackModel()
        return if (restored != null) {
            RollbackResult(success = true, restoredModel = restored, message = "Rolled back to '$restored'")
        } else {
            RollbackResult(success = false, message = "No previous model to roll back to")
        }
    }

    /**
     * Get the currently active product model name, or null if none is set.
     */
    fun getActiveModel(db: Database): String? {
        return db.getActiveModel()?.get("name")
    }

    /**
     * List all registered model versions.
     */
    fun listModels(db: Database): List<Map<String, Any?>> {
        return db.getModelRegistryEntries()
    }
}

data class ActivationResult(
    val success: Boolean,
    val message: String
)

data class RollbackResult(
    val success: Boolean,
    val restoredModel: String? = null,
    val message: String
)
