package forge.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * A code block extracted from a fenced markdown block in an LLM response.
 *
 * @property language The language identifier after the opening ```, or empty string if none.
 * @property code     The raw code content (without the fence markers).
 * @property startIndex The character index in the original response where this block starts.
 */
data class CodeBlock(
    val language: String,
    val code: String,
    val startIndex: Int
)

/**
 * Parses and transforms raw LLM response text into structured data.
 */
object ResponseParser {

    // Matches fenced code blocks: ```lang\ncode\n```
    // The language identifier is optional.
    private val CODE_BLOCK_REGEX = Regex(
        """```(\w*)\s*\n(.*?)```""",
        setOf(RegexOption.DOT_MATCHES_ALL)
    )

    // Matches <think>...</think> tags produced by deepseek-r1 and similar models.
    private val THINK_TAG_REGEX = Regex(
        """<think>.*?</think>\s*""",
        setOf(RegexOption.DOT_MATCHES_ALL)
    )

    // Matches a JSON object at the top level (greedy, outermost braces).
    private val JSON_OBJECT_REGEX = Regex(
        """\{[^{}]*(?:\{[^{}]*\}[^{}]*)*\}"""
    )

    // Matches a JSON array at the top level.
    private val JSON_ARRAY_REGEX = Regex(
        """\[[^\[\]]*(?:\[[^\[\]]*\][^\[\]]*)*\]"""
    )

    private val lenientJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // ── Public API ──────────────────────────────────────────────────────────────

    /**
     * Extract all fenced code blocks from the LLM response.
     *
     * Returns them in the order they appear. Each [CodeBlock] includes the
     * language tag (lowercased, or empty), the raw code, and the start index.
     */
    fun extractCodeBlocks(response: String): List<CodeBlock> {
        return CODE_BLOCK_REGEX.findAll(response).map { match ->
            CodeBlock(
                language = match.groupValues[1].lowercase().trim(),
                code = match.groupValues[2].trimEnd(),
                startIndex = match.range.first
            )
        }.toList()
    }

    /**
     * Extract a JSON string from the response.
     *
     * Looks for JSON inside a fenced code block first (```json ... ```),
     * then falls back to finding the first bare JSON object or array
     * in the response text. Returns null if no valid JSON is found.
     */
    fun extractJson(response: String): String? {
        // Strategy 1: look for a fenced JSON code block
        val codeBlocks = extractCodeBlocks(response)
        val jsonBlock = codeBlocks.find { it.language == "json" || it.language == "" }
        if (jsonBlock != null) {
            val candidate = jsonBlock.code.trim()
            if (isValidJson(candidate)) return candidate
        }

        // Strategy 2: find a bare JSON object in the text
        val cleaned = stripThinking(response)
        val objectMatch = JSON_OBJECT_REGEX.find(cleaned)
        if (objectMatch != null && isValidJson(objectMatch.value)) {
            return objectMatch.value
        }

        // Strategy 3: find a bare JSON array in the text
        val arrayMatch = JSON_ARRAY_REGEX.find(cleaned)
        if (arrayMatch != null && isValidJson(arrayMatch.value)) {
            return arrayMatch.value
        }

        return null
    }

    /**
     * Remove `<think>...</think>` tags that deepseek-r1 and similar reasoning
     * models emit for their chain-of-thought. Returns the response with those
     * sections stripped and leading/trailing whitespace cleaned up.
     */
    fun stripThinking(response: String): String {
        return THINK_TAG_REGEX.replace(response, "").trim()
    }

    /**
     * Clean up the response for display as Markdown.
     *
     * - Strips thinking tags.
     * - Normalizes excessive blank lines (3+ consecutive) down to 2.
     * - Trims leading/trailing whitespace.
     */
    fun toMarkdown(response: String): String {
        var cleaned = stripThinking(response)
        // Collapse runs of 3+ blank lines into exactly 2 newlines
        cleaned = cleaned.replace(Regex("""\n{3,}"""), "\n\n")
        // Normalize trailing whitespace on each line
        cleaned = cleaned.lines().joinToString("\n") { it.trimEnd() }
        return cleaned.trim()
    }

    // ── Internal helpers ────────────────────────────────────────────────────────

    private fun isValidJson(text: String): Boolean {
        return try {
            lenientJson.parseToJsonElement(text)
            true
        } catch (_: Exception) {
            false
        }
    }
}
