package forge.core.prompt

/**
 * Execution status of a single partition.
 */
enum class PartitionStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    SKIPPED
}

/**
 * The result of executing a single [PromptPartition] through the pipeline.
 */
data class PartitionResult(
    /** The partition ID this result belongs to. */
    val partitionId: String,
    /** Final execution status. */
    val status: PartitionStatus,
    /** The LLM response text (null if failed/skipped). */
    val response: String? = null,
    /** Artifacts produced by this partition, keyed by label. */
    val artifacts: Map<String, String> = emptyMap(),
    /** Wall-clock duration in milliseconds. */
    val durationMs: Long = 0,
    /** The model that was used for the LLM call. */
    val modelUsed: String? = null,
    /** Error message if the partition failed. */
    val error: String? = null
)
