package forge.evolution

import forge.EvolutionConfig
import forge.core.ForgeResult
import forge.workspace.Database

/**
 * Collects question/answer pairs from successful FORGE executions
 * and stores them as potential training data for model evolution.
 *
 * This is the entry point for Phase 7a data collection.
 * It intercepts Orchestrator results after successful execution
 * and scores/filters/stores them in the training_data table.
 */
class TrainingDataCollector(
    private val config: EvolutionConfig,
    private val scorer: QualityScorer = QualityScorer(),
    private val filter: TrainingDataFilter = TrainingDataFilter(config.piiFilterEnabled)
) {
    /**
     * Collect a Q/A pair from a completed FORGE execution.
     * Only stores if the quality score meets the threshold.
     *
     * @param db The workspace database to store training data in
     * @param userInput The original user prompt
     * @param result The FORGE execution result
     * @param sessionId Optional session identifier
     * @return The training data ID if stored, null if filtered out
     */
    fun collect(
        db: Database,
        userInput: String,
        result: ForgeResult,
        sessionId: String? = null
    ): Int? {
        if (!config.collectTrainingData) return null
        if (!config.enabled) return null

        // Score the Q/A pair
        val quality = scorer.score(
            inputPrompt = userInput,
            outputResponse = result.response,
            taskType = result.taskType.name,
            wasSuccessful = result.response.isNotBlank()
        )

        // Filter out low-quality data
        if (quality < config.qualityThreshold) return null

        // Safety filter
        val filterResult = filter.validatePair(userInput, result.response)
        if (!filterResult.passed) return null

        // Sanitize before storing
        val sanitizedInput = filter.sanitize(userInput)
        val sanitizedOutput = filter.sanitize(result.response)

        // Store in training_data table
        val id = db.insertTrainingData(
            sessionId = sessionId,
            taskId = null,
            inputPrompt = sanitizedInput,
            systemPrompt = null, // System prompt could be added if we capture it
            outputResponse = sanitizedOutput,
            taskType = result.taskType.name,
            modelUsed = result.model,
            qualityScore = quality,
            isValidated = config.autoValidateOnSuccess
        )

        return if (id > 0) id else null
    }
}
