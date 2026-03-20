package forge.evolution

import forge.workspace.Database

/**
 * Evaluates the current state of training data and recommends
 * the next step in the model evolution pipeline.
 *
 * This is the "advisor" that tells the user what's possible
 * given their current data and hardware constraints.
 */
class ModelEvolutionPlanner {

    enum class ReadinessLevel {
        NOT_READY,          // No training data yet
        PROMPT_OPTIMIZATION,// Can improve via better prompting (always available)
        RAG_READY,         // 100+ validated examples — can use as retrieval context
        LORA_POSSIBLE,     // 500+ validated examples — LoRA fine-tuning possible
        LORA_RECOMMENDED   // 1000+ validated examples — LoRA recommended
    }

    data class EvolutionStatus(
        val totalExamples: Int,
        val validatedExamples: Int,
        val averageQuality: Double,
        val readiness: ReadinessLevel,
        val nextMilestone: String,
        val recommendation: String,
        val activeModel: String?,
        val registeredModels: Int
    )

    /**
     * Assess the current evolution readiness based on available training data.
     */
    fun assess(db: Database): EvolutionStatus {
        val total = db.getTrainingDataCount()
        val validated = db.getValidatedTrainingDataCount()
        val activeModel = db.getActiveModel()?.get("name")
        val registeredModels = db.getModelRegistryEntries().size

        // Calculate average quality from validated examples
        val avgQuality = if (validated > 0) {
            // Estimate from the export query (we don't have a dedicated avg query)
            val sample = db.getTrainingDataForExport(0.0, limit = 100)
            if (sample.isNotEmpty()) {
                sample.mapNotNull { it["quality"] as? Double }.average()
            } else 0.0
        } else 0.0

        val readiness = when {
            validated >= 1000 -> ReadinessLevel.LORA_RECOMMENDED
            validated >= 500 -> ReadinessLevel.LORA_POSSIBLE
            validated >= 100 -> ReadinessLevel.RAG_READY
            total > 0 -> ReadinessLevel.PROMPT_OPTIMIZATION
            else -> ReadinessLevel.NOT_READY
        }

        val nextMilestone = when (readiness) {
            ReadinessLevel.NOT_READY -> "Start using FORGE to collect training data (need 100+ examples)"
            ReadinessLevel.PROMPT_OPTIMIZATION -> "Collect ${100 - validated} more validated examples for RAG-based improvement"
            ReadinessLevel.RAG_READY -> "Collect ${500 - validated} more validated examples for LoRA fine-tuning"
            ReadinessLevel.LORA_POSSIBLE -> "Collect ${1000 - validated} more examples for optimal LoRA quality"
            ReadinessLevel.LORA_RECOMMENDED -> "Ready for LoRA fine-tuning with $validated validated examples"
        }

        val recommendation = when (readiness) {
            ReadinessLevel.NOT_READY ->
                "Keep using FORGE normally. Training data is automatically collected from successful interactions."
            ReadinessLevel.PROMPT_OPTIMIZATION ->
                "Rate responses (thumbs up/down) to validate training data. You have $total examples, $validated validated."
            ReadinessLevel.RAG_READY ->
                "You have enough data for RAG-based improvement. Export a dataset and configure retrieval-augmented context."
            ReadinessLevel.LORA_POSSIBLE ->
                "LoRA fine-tuning is possible. Export dataset and use a LoRA training tool (e.g., unsloth, axolotl). Requires 16GB+ VRAM."
            ReadinessLevel.LORA_RECOMMENDED ->
                "Excellent data quality! LoRA fine-tuning is strongly recommended. This can significantly improve FORGE's performance on your specific codebase patterns."
        }

        return EvolutionStatus(
            totalExamples = total,
            validatedExamples = validated,
            averageQuality = avgQuality,
            readiness = readiness,
            nextMilestone = nextMilestone,
            recommendation = recommendation,
            activeModel = activeModel,
            registeredModels = registeredModels
        )
    }
}
