package forge.core.prompt

/**
 * Types of trace events emitted during prompt decomposition and execution.
 */
enum class TraceEventType {
    /** Raw prompt received and intake logged. */
    PROMPT_INTAKE,
    /** Prompt archetypes recognized via heuristic scoring. */
    PROMPT_RECOGNIZED,
    /** Complexity level determined (SIMPLE, COMPOUND, MULTI_STAGE). */
    COMPLEXITY_DETECTED,
    /** Partitions created (heuristic or LLM decomposition). */
    PARTITIONS_CREATED,
    /** Dependency graph built and topologically sorted into layers. */
    DEPENDENCY_GRAPH_BUILT,
    /** An execution layer has started (all partitions in that layer begin). */
    EXECUTION_LAYER_STARTED,
    /** A single partition has started execution. */
    PARTITION_STARTED,
    /** A single partition completed successfully. */
    PARTITION_COMPLETED,
    /** A single partition failed. */
    PARTITION_FAILED,
    /** A single partition was skipped due to dependency failure. */
    PARTITION_SKIPPED,
    /** Reconciliation phase started. */
    RECONCILIATION_STARTED,
    /** A contradiction was detected during reconciliation. */
    CONTRADICTION_FOUND,
    /** Reconciliation completed. */
    RECONCILIATION_COMPLETED,
    /** Synthesis phase started. */
    SYNTHESIS_STARTED,
    /** Synthesis completed, final response produced. */
    SYNTHESIS_COMPLETED
}

/**
 * A single typed trace event emitted during the decomposition pipeline.
 */
data class TypedTraceEvent(
    /** The type of event. */
    val type: TraceEventType,
    /** Optional partition ID if the event is partition-specific. */
    val partitionId: String? = null,
    /** Human-readable description of the event. */
    val detail: String,
    /** Timestamp when the event occurred (epoch millis). */
    val timestampMs: Long = System.currentTimeMillis(),
    /** Duration in milliseconds if the event represents a completed action. */
    val durationMs: Long? = null
)

/**
 * Collects [TypedTraceEvent] entries throughout the decomposition pipeline.
 * Thread-safe: uses synchronized access to the event list.
 *
 * Injected into [PromptAnalyzer], [ExecutionPlanner], [ParallelExecutor],
 * [Reconciler], and [ResultSynthesizer] to provide a unified execution trace.
 */
class DecompositionTracer {
    private val events = mutableListOf<TypedTraceEvent>()
    private val lock = Any()

    /**
     * Record a trace event.
     */
    fun record(event: TypedTraceEvent) {
        synchronized(lock) {
            events.add(event)
        }
    }

    /**
     * Record a trace event using individual parameters.
     */
    fun record(
        type: TraceEventType,
        detail: String,
        partitionId: String? = null,
        durationMs: Long? = null
    ) {
        record(TypedTraceEvent(
            type = type,
            partitionId = partitionId,
            detail = detail,
            durationMs = durationMs
        ))
    }

    /**
     * Get all recorded events in chronological order.
     */
    fun getEvents(): List<TypedTraceEvent> {
        synchronized(lock) {
            return events.toList()
        }
    }

    /**
     * Total number of events recorded.
     */
    val size: Int get() = synchronized(lock) { events.size }

    /**
     * Whether any events have been recorded.
     */
    val isEmpty: Boolean get() = synchronized(lock) { events.isEmpty() }

    /**
     * Human-readable summary of the decomposition trace, suitable for console display.
     */
    fun summary(): String {
        val snapshot = getEvents()
        if (snapshot.isEmpty()) return "  (no decomposition trace events)"

        return buildString {
            appendLine("  Decomposition Trace (${snapshot.size} events):")
            for (event in snapshot) {
                val prefix = when (event.type) {
                    TraceEventType.PROMPT_INTAKE -> "  >"
                    TraceEventType.PROMPT_RECOGNIZED -> "  >"
                    TraceEventType.COMPLEXITY_DETECTED -> "  >"
                    TraceEventType.PARTITIONS_CREATED -> "  +"
                    TraceEventType.DEPENDENCY_GRAPH_BUILT -> "  +"
                    TraceEventType.EXECUTION_LAYER_STARTED -> "  |"
                    TraceEventType.PARTITION_STARTED -> "  | >"
                    TraceEventType.PARTITION_COMPLETED -> "  | <"
                    TraceEventType.PARTITION_FAILED -> "  | !"
                    TraceEventType.PARTITION_SKIPPED -> "  | ~"
                    TraceEventType.RECONCILIATION_STARTED -> "  *"
                    TraceEventType.CONTRADICTION_FOUND -> "  * !"
                    TraceEventType.RECONCILIATION_COMPLETED -> "  *"
                    TraceEventType.SYNTHESIS_STARTED -> "  ="
                    TraceEventType.SYNTHESIS_COMPLETED -> "  ="
                }
                val duration = if (event.durationMs != null) " (${event.durationMs}ms)" else ""
                val partition = if (event.partitionId != null) " [${event.partitionId}]" else ""
                appendLine("$prefix${partition} ${event.detail}$duration")
            }
        }
    }
}
