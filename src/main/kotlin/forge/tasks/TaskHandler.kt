package forge.tasks

import forge.ForgeConfig
import forge.core.TaskType

/**
 * Abstract base class for task-specific handlers. Each handler knows how to
 * build a specialised prompt section for its [taskType], given the evidence
 * and context chunks collected by the pipeline.
 *
 * Subclasses override [buildSpecificPrompt] to inject task-specific instructions,
 * framing, and formatting requirements into the LLM prompt. The resulting string
 * is appended to the general prompt assembled by the [forge.llm.PromptBuilder].
 *
 * Handlers are stateless and can be shared across concurrent pipeline runs.
 */
abstract class TaskHandler(protected val config: ForgeConfig) {

    /**
     * The [TaskType] this handler is responsible for.
     */
    abstract val taskType: TaskType

    /**
     * Builds a task-specific prompt section to be appended to the general prompt.
     *
     * @param evidence map of evidence key-value pairs collected during the
     *                 evidence-collection stage (e.g. "BUILD_SYSTEM:build_system" -> "Gradle")
     * @param chunks   list of code chunks retrieved via semantic search
     * @return a formatted string to be included in the LLM user prompt
     */
    abstract fun buildSpecificPrompt(
        evidence: Map<String, String>,
        chunks: List<String>
    ): String

    // ── Utility methods shared across handlers ──────────────────────────────

    /**
     * Filters evidence entries whose key starts with the given [category] prefix.
     *
     * @param evidence the full evidence map
     * @param category the category prefix to filter by (e.g. "BUILD_SYSTEM")
     * @return filtered map containing only entries matching the category
     */
    protected fun filterEvidence(
        evidence: Map<String, String>,
        category: String
    ): Map<String, String> {
        return evidence.filter { (key, _) -> key.startsWith("$category:") }
    }

    /**
     * Formats evidence entries for a single category into a bulleted markdown
     * section suitable for embedding in a prompt.
     *
     * @param evidence     the full evidence map
     * @param category     the category prefix to filter by
     * @param sectionTitle the heading to use for this section
     * @return a markdown section string, or empty string if no matching evidence
     */
    protected fun formatEvidenceSection(
        evidence: Map<String, String>,
        category: String,
        sectionTitle: String
    ): String {
        val filtered = filterEvidence(evidence, category)
        if (filtered.isEmpty()) return ""

        val builder = StringBuilder()
        builder.appendLine("### $sectionTitle")
        for ((key, value) in filtered) {
            val shortKey = key.substringAfter(":")
            builder.appendLine("- **$shortKey**: $value")
        }
        return builder.toString()
    }

    /**
     * Formats code chunks as numbered sections separated by visual delimiters.
     *
     * @param chunks    the list of code chunk contents
     * @param maxChunks maximum number of chunks to include (to limit prompt size)
     * @return formatted string of chunks, or a placeholder if none available
     */
    protected fun formatChunks(chunks: List<String>, maxChunks: Int = 15): String {
        if (chunks.isEmpty()) return "(no relevant code chunks found)"

        return chunks.take(maxChunks).mapIndexed { idx, chunk ->
            "--- Chunk ${idx + 1} ---\n$chunk"
        }.joinToString("\n\n")
    }

    /**
     * Creates a standard instructions block for the LLM.
     *
     * @param instructions the list of instruction bullet points
     * @return a markdown section with bulleted instructions
     */
    protected fun instructionBlock(vararg instructions: String): String {
        return buildString {
            appendLine("## Instructions")
            for (instruction in instructions) {
                appendLine("- $instruction")
            }
        }
    }
}
