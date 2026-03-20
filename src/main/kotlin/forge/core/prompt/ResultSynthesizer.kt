package forge.core.prompt

import forge.ForgeConfig
import forge.llm.ChatMessage
import forge.llm.OllamaClient

/**
 * Strategy for handling partition failures during synthesis.
 */
enum class FailureStrategy {
    /** All blocking partitions succeeded — produce full synthesis. */
    FULL_SYNTHESIS,
    /** Some non-blocking partitions failed — produce partial synthesis with notes. */
    PARTIAL_SYNTHESIS,
    /** A blocking partition failed — cannot produce meaningful synthesis. */
    BLOCKED
}

/**
 * Merges results from multiple partition executions into a single coherent response.
 *
 * For SIMPLE prompts: returns the single partition result directly (no LLM call).
 * For COMPOUND/MULTI_STAGE: uses the LLM to synthesize a unified response from
 * all partition results, noting any contradictions or failures.
 */
class ResultSynthesizer(
    private val config: ForgeConfig,
    private val ollamaClient: OllamaClient
) {
    /**
     * Synthesize all partition results into a final response string.
     *
     * @param reconciliation optional reconciliation report; if present, contradictions
     *                       and missing artifacts are included in the synthesis prompt.
     */
    suspend fun synthesize(
        plan: ExecutionPlan,
        results: Map<String, PartitionResult>,
        reconciliation: ReconciliationReport? = null
    ): String {
        // Single partition — return directly, no synthesis needed
        if (plan.partitionCount == 1) {
            val singleResult = results.values.firstOrNull()
            return singleResult?.response ?: "No results produced."
        }

        // Check failure strategy
        val strategy = determineFailureStrategy(plan, results)

        return when (strategy) {
            FailureStrategy.BLOCKED -> buildBlockedResponse(plan, results, reconciliation)
            FailureStrategy.PARTIAL_SYNTHESIS -> llmSynthesize(plan, results, partial = true, reconciliation = reconciliation)
            FailureStrategy.FULL_SYNTHESIS -> llmSynthesize(plan, results, partial = false, reconciliation = reconciliation)
        }
    }

    /**
     * Determine how to handle the results based on partition success/failure.
     */
    private fun determineFailureStrategy(
        plan: ExecutionPlan,
        results: Map<String, PartitionResult>
    ): FailureStrategy {
        val blockingFailed = plan.partitions.any { partition ->
            partition.isBlocking && results[partition.id]?.status == PartitionStatus.FAILED
        }
        if (blockingFailed) return FailureStrategy.BLOCKED

        val anyFailed = results.values.any { it.status == PartitionStatus.FAILED }
        if (anyFailed) return FailureStrategy.PARTIAL_SYNTHESIS

        return FailureStrategy.FULL_SYNTHESIS
    }

    /**
     * Build a response when a blocking partition has failed.
     */
    private fun buildBlockedResponse(
        plan: ExecutionPlan,
        results: Map<String, PartitionResult>,
        reconciliation: ReconciliationReport? = null
    ): String {
        val sb = StringBuilder()
        sb.appendLine("## Partial Results")
        sb.appendLine()
        sb.appendLine("Some critical analysis partitions failed. Showing available results:")
        sb.appendLine()

        for (partition in plan.partitions) {
            val result = results[partition.id]
            val statusIcon = when (result?.status) {
                PartitionStatus.COMPLETED -> "[OK]"
                PartitionStatus.FAILED -> "[FAILED]"
                PartitionStatus.SKIPPED -> "[SKIPPED]"
                else -> "[?]"
            }
            sb.appendLine("### $statusIcon ${partition.semanticLabel}")
            if (result?.response != null) {
                sb.appendLine(result.response)
            } else if (result?.error != null) {
                sb.appendLine("Error: ${result.error}")
            }
            sb.appendLine()
        }

        // Append reconciliation issues if present
        if (reconciliation != null && reconciliation.hasIssues) {
            sb.appendLine("### Reconciliation Notes")
            for (c in reconciliation.contradictions) {
                sb.appendLine("- **Contradiction** (${c.severity}): ${c.description}")
            }
            for (m in reconciliation.missingArtifacts) {
                sb.appendLine("- **Missing artifact**: $m")
            }
            for (u in reconciliation.unresolvedDeps) {
                sb.appendLine("- **Unresolved dependency**: $u")
            }
            sb.appendLine()
        }

        return sb.toString()
    }

    /**
     * Use the LLM to synthesize multiple partition results into one response.
     */
    private suspend fun llmSynthesize(
        plan: ExecutionPlan,
        results: Map<String, PartitionResult>,
        partial: Boolean,
        reconciliation: ReconciliationReport? = null
    ): String {
        if (!config.decomposition.synthesisEnabled) {
            return concatenateResults(plan, results)
        }

        val partitionSummaries = plan.partitions.mapNotNull { partition ->
            val result = results[partition.id] ?: return@mapNotNull null
            when (result.status) {
                PartitionStatus.COMPLETED -> "=== ${partition.semanticLabel} (${partition.id}) ===\n${result.response}"
                PartitionStatus.FAILED -> "=== ${partition.semanticLabel} (${partition.id}) [FAILED] ===\nError: ${result.error}"
                PartitionStatus.SKIPPED -> "=== ${partition.semanticLabel} (${partition.id}) [SKIPPED] ===\nSkipped: ${result.error}"
                else -> null
            }
        }.joinToString("\n\n")

        val reconciliationContext = if (reconciliation != null && reconciliation.hasIssues) {
            buildString {
                appendLine("- IMPORTANT: The reconciliation engine detected the following issues across partition outputs:")
                for (c in reconciliation.contradictions) {
                    appendLine("  * Contradiction (${c.severity}): ${c.description}")
                }
                for (m in reconciliation.missingArtifacts) {
                    appendLine("  * Missing artifact: $m")
                }
                for (u in reconciliation.unresolvedDeps) {
                    appendLine("  * Unresolved dependency: $u")
                }
                appendLine("- Address these contradictions explicitly in your synthesis. State which finding is more likely correct and why, or note both perspectives.")
            }
        } else ""

        val systemPrompt = """You are a synthesis engine. Merge the following analysis results into one coherent, well-structured response.

Rules:
- Preserve all findings from each analysis partition.
- Organize the response with clear sections using markdown headers.
- Note any contradictions between partition results.
- Maintain traceability: reference which analysis produced each finding.
- If some partitions failed or were skipped, note this and work with available results.
${if (partial) "- Some partitions failed. Produce the best possible synthesis from available results." else ""}
$reconciliationContext"""

        val userPrompt = """Original prompt: ${plan.originalPrompt}

Analysis results from ${plan.partitionCount} partitions:

$partitionSummaries

Synthesize these into a single coherent response."""

        val messages = listOf(
            ChatMessage(role = "system", content = systemPrompt),
            ChatMessage(role = "user", content = userPrompt)
        )

        return try {
            ollamaClient.chat(config.models.reason, messages)
        } catch (_: Exception) {
            // Fallback: concatenate results if LLM synthesis fails
            concatenateResults(plan, results)
        }
    }

    /**
     * Simple concatenation fallback when LLM synthesis is disabled or fails.
     */
    private fun concatenateResults(
        plan: ExecutionPlan,
        results: Map<String, PartitionResult>
    ): String {
        val sb = StringBuilder()
        for (partition in plan.partitions) {
            val result = results[partition.id] ?: continue
            sb.appendLine("## ${partition.semanticLabel}")
            sb.appendLine()
            when (result.status) {
                PartitionStatus.COMPLETED -> sb.appendLine(result.response)
                PartitionStatus.FAILED -> sb.appendLine("*Failed: ${result.error}*")
                PartitionStatus.SKIPPED -> sb.appendLine("*Skipped: ${result.error}*")
                else -> sb.appendLine("*No result*")
            }
            sb.appendLine()
        }
        return sb.toString()
    }
}
