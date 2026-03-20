package forge.core.prompt

/**
 * The expected shape of a partition's output. Used to instruct the LLM
 * on how to format its response for a specific partition.
 */
enum class OutputShape(val instruction: String) {
    /** Free-form analytical narrative with findings and recommendations. */
    ANALYSIS("Provide a structured analysis with findings, evidence, and recommendations."),
    /** One or more code blocks with implementation. */
    CODE_BLOCK("Provide implementation code in fenced code blocks with language tags."),
    /** A JSON-structured response. */
    STRUCTURED_JSON("Respond with a well-structured JSON object."),
    /** Narrative explanation or documentation. */
    NARRATIVE("Provide a clear, well-organized narrative explanation."),
    /** A checklist of items or steps. */
    CHECKLIST("Provide a markdown checklist with actionable items."),
    /** A review table with findings, severity, and recommendations. */
    REVIEW_TABLE("Provide findings in a structured table format with severity and recommendations.")
}

/**
 * A structured context pack prepared for a single partition's LLM call.
 * Encapsulates everything the model needs to produce a focused, well-shaped response.
 *
 * This implements the specification's requirement for structured model communication:
 * exact subtask, expected output shape, evidence boundaries, target scope, and model role.
 */
data class ContextPack(
    /** The partition this context pack is for. */
    val partitionId: String,
    /** The exact sub-task instruction (may be enriched with upstream context). */
    val subtask: String,
    /** The expected shape of the response. */
    val expectedOutputShape: OutputShape,
    /** Evidence categories relevant to this partition. */
    val evidenceBoundaries: List<String> = emptyList(),
    /** Optional file/module/package scope constraint. */
    val targetScope: String?,
    /** Optional model role override for this partition (null = use default). */
    val modelRole: String? = null,
    /** Summarized results from upstream partitions (dependency context). */
    val upstreamContext: String? = null,
    /** Maximum response tokens budget for this partition. */
    val maxResponseTokens: Int = 4000
) {
    /**
     * Build a system-level instruction prefix that guides the LLM on output format.
     */
    fun outputShapeInstruction(): String = buildString {
        appendLine("Output format: ${expectedOutputShape.instruction}")
        if (targetScope != null) {
            appendLine("Focus scope: $targetScope")
        }
        if (evidenceBoundaries.isNotEmpty()) {
            appendLine("Evidence boundaries: ${evidenceBoundaries.joinToString(", ")}")
        }
    }

    /**
     * Build the full enriched prompt for this partition, combining the subtask
     * with upstream context and output shape instructions.
     */
    fun buildPrompt(): String = buildString {
        appendLine(outputShapeInstruction())
        appendLine()
        appendLine(subtask)
        if (upstreamContext != null) {
            appendLine()
            appendLine("Previous analysis context:")
            appendLine(upstreamContext)
        }
    }
}
