package forge.evolution

/**
 * Scores question/answer pairs for suitability as training data.
 *
 * Quality signals:
 * - Response length (too short = low quality, too long = noise)
 * - Presence of code blocks (indicates concrete generation)
 * - Presence of structured analysis (headings, lists)
 * - Absence of error/failure indicators
 * - Task completion (non-empty response from successful execution)
 */
class QualityScorer {

    /**
     * Score a Q/A pair from 0.0 (unusable) to 1.0 (excellent training data).
     */
    fun score(
        inputPrompt: String,
        outputResponse: String,
        taskType: String?,
        wasSuccessful: Boolean
    ): Double {
        if (!wasSuccessful) return 0.0
        if (outputResponse.isBlank()) return 0.0
        if (inputPrompt.isBlank()) return 0.0

        var score = 0.3 // Base score for any successful completion

        // Length quality: 100-5000 chars is ideal
        val len = outputResponse.length
        score += when {
            len < 50 -> 0.0
            len < 100 -> 0.05
            len < 500 -> 0.1
            len < 2000 -> 0.15
            len < 5000 -> 0.2
            len < 10000 -> 0.15
            else -> 0.1  // Very long responses may contain noise
        }

        // Code block presence (valuable for code generation tasks)
        if (outputResponse.contains("```")) {
            score += 0.1
        }

        // Structured content (headings, lists)
        if (outputResponse.contains("## ") || outputResponse.contains("### ")) {
            score += 0.05
        }
        if (outputResponse.contains("- ") || outputResponse.contains("1. ")) {
            score += 0.05
        }

        // Negative signals
        if (outputResponse.contains("I don't know") ||
            outputResponse.contains("I cannot") ||
            outputResponse.contains("I'm not sure") ||
            outputResponse.contains("error occurred")) {
            score -= 0.2
        }

        // Task type bonus (some types produce better training data)
        when (taskType?.uppercase()) {
            "ARCHITECTURE_REVIEW", "CODE_QUALITY_REVIEW", "SECURITY_REVIEW" -> score += 0.1
            "IMPLEMENT_FEATURE", "API_DESIGN" -> score += 0.1
            "REPO_ANALYSIS" -> score += 0.05
        }

        // Input quality: longer, more specific prompts make better training data
        val inputWords = inputPrompt.split("\\s+".toRegex()).size
        score += when {
            inputWords < 3 -> 0.0
            inputWords < 10 -> 0.05
            inputWords < 50 -> 0.1
            else -> 0.05
        }

        return score.coerceIn(0.0, 1.0)
    }
}
