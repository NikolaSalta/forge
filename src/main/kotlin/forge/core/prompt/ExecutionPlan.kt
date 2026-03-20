package forge.core.prompt

import java.util.UUID

/**
 * Complexity classification of the incoming prompt.
 */
enum class PromptComplexity {
    /** Single intent — fast path, no decomposition needed. */
    SIMPLE,
    /** 2–3 intents — decompose and potentially run in parallel. */
    COMPOUND,
    /** 4+ intents or explicit sequencing — full DAG execution. */
    MULTI_STAGE
}

/**
 * A fully materialized execution plan ready for the [ParallelExecutor].
 * Contains the original prompt, its decomposed partitions, and the
 * topologically sorted execution layers.
 */
data class ExecutionPlan(
    /** Unique request identifier for traceability. */
    val requestId: String = "req-${UUID.randomUUID().toString().take(8)}",
    /** Session identifier for cross-request traceability (null if session tracking disabled). */
    val sessionId: String? = null,
    /** The original user prompt before decomposition. */
    val originalPrompt: String,
    /** Detected complexity of the prompt. */
    val complexity: PromptComplexity,
    /** All partitions derived from the prompt. */
    val partitions: List<PromptPartition>,
    /**
     * Topologically sorted execution layers.
     * Each inner list contains partition IDs that can run in parallel.
     * Layers are executed sequentially (layer 0 first, then layer 1, etc.).
     */
    val executionLayers: List<List<String>>,
    /** The highest-scoring archetype. */
    val primaryArchetype: PromptArchetype,
    /** Additional detected archetypes (excluding primary). */
    val secondaryArchetypes: List<PromptArchetype> = emptyList()
) {
    /** Total number of partitions. */
    val partitionCount: Int get() = partitions.size

    /** Look up a partition by ID. */
    fun partitionById(id: String): PromptPartition? = partitions.find { it.id == id }
}
