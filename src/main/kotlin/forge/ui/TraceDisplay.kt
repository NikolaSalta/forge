package forge.ui

/**
 * Simple trace log that collects key-value pairs during pipeline execution.
 *
 * Each entry represents a single observed fact, timing measurement, or
 * status indicator gathered as the pipeline progresses through its stages.
 * The log can be displayed at the end of a run (or mid-run) via [TraceDisplay].
 *
 * Thread-safe: the internal list is synchronized so that concurrent pipeline
 * stages can safely record entries.
 */
class TraceLog {

    private val entries = mutableListOf<Pair<String, String>>()
    private val lock = Any()

    /**
     * Records a single key-value pair in the trace log.
     *
     * @param key   short label describing what was measured (e.g. "model", "chunks_found")
     * @param value the measured or observed value
     */
    fun record(key: String, value: String) {
        synchronized(lock) {
            entries.add(key to value)
        }
    }

    /**
     * Returns an immutable snapshot of all recorded entries in insertion order.
     */
    fun getEntries(): List<Pair<String, String>> {
        synchronized(lock) {
            return entries.toList()
        }
    }

    /**
     * Removes all recorded entries from the log. Typically called between
     * pipeline runs so that the next run starts with a clean trace.
     */
    fun clear() {
        synchronized(lock) {
            entries.clear()
        }
    }

    /**
     * Returns the number of entries currently in the log.
     */
    fun size(): Int {
        synchronized(lock) {
            return entries.size
        }
    }

    /**
     * Returns true if there are no entries in the log.
     */
    fun isEmpty(): Boolean {
        synchronized(lock) {
            return entries.isEmpty()
        }
    }
}

/**
 * Display helper that renders a [TraceLog] to the console using the
 * [ForgeConsole] trace formatting methods.
 *
 * Usage:
 * ```
 * val trace = TraceLog()
 * trace.record("intent", "REPO_ANALYSIS")
 * trace.record("confidence", "0.92")
 * trace.record("model", "deepseek-r1:8b")
 *
 * val display = TraceDisplay(console)
 * display.display(trace)
 * ```
 *
 * @param console the [ForgeConsole] instance used for rendering output
 */
class TraceDisplay(private val console: ForgeConsole) {

    /**
     * Renders all entries in the [trace] to the console as indented
     * key-value lines. Each entry is printed via [ForgeConsole.traceInfo],
     * which applies the standard dimmed styling with tree-drawing characters.
     *
     * If the trace is empty, nothing is printed.
     *
     * @param trace the trace log to display
     */
    fun display(trace: TraceLog) {
        val entries = trace.getEntries()
        if (entries.isEmpty()) return

        for ((index, entry) in entries.withIndex()) {
            val (key, value) = entry
            val prefix = if (index < entries.size - 1) "├─" else "└─"
            console.traceInfo("$prefix $key: $value")
        }
    }

    /**
     * Renders a trace log with an optional section header. The header is
     * printed as a stage indicator before the trace entries.
     *
     * @param title the section header text
     * @param trace the trace log to display
     */
    fun displayWithTitle(title: String, trace: TraceLog) {
        val entries = trace.getEntries()
        if (entries.isEmpty()) return

        console.stage(title)
        display(trace)
    }
}
