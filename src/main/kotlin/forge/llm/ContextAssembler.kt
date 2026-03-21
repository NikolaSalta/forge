package forge.llm

import forge.ForgeConfig
import forge.workspace.ModuleRecord

/**
 * Result of context assembly with token budget tracking.
 */
data class AssembledContext(
    val moduleContext: String,
    val evidenceContext: String,
    val codeChunks: List<String>,
    val attachedFilesContext: String,
    val totalTokenEstimate: Int,
    val truncated: Boolean
)

/**
 * Assembles LLM prompt context from multiple sources while respecting
 * a token budget. Prioritizes the most relevant information.
 *
 * Budget allocation:
 * 1. Module context (plugin.xml summary, dependencies) — ~500 tokens
 * 2. Evidence (collected facts about the repo) — ~500 tokens
 * 3. Attached files (user-provided documents) — variable
 * 4. Code chunks (semantic search results) — fills remaining budget
 */
class ContextAssembler(private val config: ForgeConfig) {

    private val tokenBudget = config.scale.tokenBudget

    /**
     * Assemble a complete context for the LLM within the token budget.
     */
    fun assemble(
        relevantModules: List<ModuleRecord>,
        scoredChunkTexts: List<String>,
        evidence: Map<String, String>,
        attachedFiles: Map<String, String>,
        focusModule: String? = null
    ): AssembledContext {
        var remainingTokens = tokenBudget

        // 1. Module context (highest priority)
        val moduleCtx = buildModuleContext(relevantModules, focusModule)
        val moduleTokens = estimateTokens(moduleCtx)
        remainingTokens -= moduleTokens

        // 2. Evidence context
        val evidenceCtx = buildEvidenceContext(evidence)
        val evidenceTokens = estimateTokens(evidenceCtx)
        remainingTokens -= evidenceTokens

        // 3. Attached files (if any)
        val attachedCtx = buildAttachedContext(attachedFiles, remainingTokens / 2)
        val attachedTokens = estimateTokens(attachedCtx)
        remainingTokens -= attachedTokens

        // 4. Code chunks (fill remaining budget)
        val (chunks, truncated) = selectChunksWithinBudget(scoredChunkTexts, remainingTokens)

        return AssembledContext(
            moduleContext = moduleCtx,
            evidenceContext = evidenceCtx,
            codeChunks = chunks,
            attachedFilesContext = attachedCtx,
            totalTokenEstimate = tokenBudget - remainingTokens + chunks.sumOf { estimateTokens(it) },
            truncated = truncated
        )
    }

    private fun buildModuleContext(modules: List<ModuleRecord>, focusModule: String?): String {
        if (modules.isEmpty()) return ""

        return buildString {
            if (focusModule != null) {
                appendLine("## Focused Module: $focusModule")
            }
            appendLine("## Relevant IntelliJ Modules (${modules.size}):")
            for (module in modules.take(50_000)) {
                appendLine("- **${module.name}** [${module.moduleType ?: "unknown"}] (${module.fileCount} files)")
                if (module.summary != null) {
                    appendLine("  ${module.summary.take(1_000_000)}")
                }
                if (module.dependencies != null) {
                    appendLine("  Dependencies: ${module.dependencies.take(500_000)}")
                }
            }
        }
    }

    private fun buildEvidenceContext(evidence: Map<String, String>): String {
        if (evidence.isEmpty()) return ""

        return buildString {
            appendLine("## Evidence:")
            for ((key, value) in evidence.entries.take(100_000)) {
                appendLine("- **$key**: $value")
            }
        }
    }

    private fun buildAttachedContext(files: Map<String, String>, maxTokens: Int): String {
        if (files.isEmpty()) return ""

        return buildString {
            var usedTokens = 0
            for ((name, content) in files) {
                val header = "=== FILE: $name ===\n"
                val headerTokens = estimateTokens(header)
                val contentTokens = estimateTokens(content)

                if (usedTokens + headerTokens + contentTokens > maxTokens) {
                    // Truncate this file's content
                    val availableTokens = maxTokens - usedTokens - headerTokens
                    if (availableTokens > 100) {
                        append(header)
                        append(content.take(availableTokens * 4)) // ~4 chars per token
                        appendLine("\n[... truncated ...]")
                    }
                    break
                }

                append(header)
                appendLine(content)
                appendLine()
                usedTokens += headerTokens + contentTokens
            }
        }
    }

    private fun selectChunksWithinBudget(
        chunks: List<String>,
        budget: Int
    ): Pair<List<String>, Boolean> {
        val result = mutableListOf<String>()
        var usedTokens = 0

        for (chunk in chunks) {
            val tokens = estimateTokens(chunk)
            if (usedTokens + tokens > budget) {
                return Pair(result, true) // Truncated
            }
            result.add(chunk)
            usedTokens += tokens
        }

        return Pair(result, false) // Not truncated
    }

    /**
     * Estimate token count. Rough heuristic: ~4 characters per token for code/English.
     */
    private fun estimateTokens(text: String): Int = (text.length / 4).coerceAtLeast(1)
}
