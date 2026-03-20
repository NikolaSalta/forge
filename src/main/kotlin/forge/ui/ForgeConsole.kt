package forge.ui

import com.github.ajalt.mordant.rendering.TextColors.blue
import com.github.ajalt.mordant.rendering.TextColors.brightCyan
import com.github.ajalt.mordant.rendering.TextColors.brightWhite
import com.github.ajalt.mordant.rendering.TextColors.cyan
import com.github.ajalt.mordant.rendering.TextColors.green
import com.github.ajalt.mordant.rendering.TextColors.red
import com.github.ajalt.mordant.rendering.TextColors.white
import com.github.ajalt.mordant.rendering.TextColors.yellow
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.TextStyles.dim
import com.github.ajalt.mordant.table.table
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.widgets.Panel
import forge.core.prompt.ContradictionSeverity
import forge.core.prompt.DecompositionTracer
import forge.core.prompt.ExecutionPlan
import forge.core.prompt.PromptComplexity
import forge.core.prompt.PromptPartition
import forge.core.prompt.ReconciliationReport
import forge.retrieval.GateResult
import forge.workspace.EvidenceRecord

/**
 * Rich terminal output for the Forge CLI. Uses the Mordant library for ANSI
 * colour, tables, panels, and other formatted output.
 *
 * All user-facing output flows through this class so that it is easy to
 * adjust styling, silence trace output, or redirect to a log.
 *
 * @param showTrace if true, trace information lines are displayed during pipeline execution
 */
class ForgeConsole(private val showTrace: Boolean = true) {

    private val terminal = Terminal()

    // ── Banner ───────────────────────────────────────────────────────────────

    /**
     * Prints the Forge ASCII art banner in cyan.
     */
    fun banner() {
        val art = """
            |
            |  ███████╗ ██████╗ ██████╗  ██████╗ ███████╗
            |  ██╔════╝██╔═══██╗██╔══██╗██╔════╝ ██╔════╝
            |  █████╗  ██║   ██║██████╔╝██║  ███╗█████╗
            |  ██╔══╝  ██║   ██║██╔══██╗██║   ██║██╔══╝
            |  ██║     ╚██████╔╝██║  ██║╚██████╔╝███████╗
            |  ╚═╝      ╚═════╝ ╚═╝  ╚═╝ ╚═════╝ ╚══════╝
            |
            |  Local AI Code Intelligence
            |
        """.trimMargin()

        terminal.println(cyan(art))
    }

    // ── Stage headers ────────────────────────────────────────────────────────

    /**
     * Prints a pipeline stage header inside a bordered panel.
     * Used to visually demarcate each phase of the pipeline execution.
     *
     * @param name the stage name, e.g. "Repository Scanning"
     */
    fun stage(name: String) {
        val panel = Panel(
            content = bold(brightWhite(name)),
            title = dim("Stage")
        )
        terminal.println(panel)
    }

    // ── Trace output ─────────────────────────────────────────────────────────

    /**
     * Prints an indented, dimmed trace information line. Only shown when
     * [showTrace] is true. Used for sub-step details during pipeline execution.
     *
     * @param message the trace message to display
     */
    fun traceInfo(message: String) {
        if (showTrace) {
            terminal.println(dim("    $message"))
        }
    }

    // ── Evidence table ───────────────────────────────────────────────────────

    /**
     * Renders a formatted evidence summary table showing the collected evidence
     * records and any missing categories indicated by the [gate] result.
     *
     * The table has three columns: Category, Value, and Status.
     * Each evidence record is shown with a green check mark; missing categories
     * are appended with a red cross.
     *
     * @param evidence the list of collected evidence records from the database
     * @param gate     the gate result indicating which categories passed/failed
     */
    fun evidenceTable(evidence: List<EvidenceRecord>, gate: GateResult) {
        if (evidence.isEmpty() && gate.missing.isEmpty()) return

        terminal.println()
        terminal.println(bold("Evidence Summary"))

        if (evidence.isNotEmpty()) {
            val t = table {
                header {
                    row(brightWhite("Category"), brightWhite("Value"), brightWhite("Status"))
                }
                body {
                    val displayEntries = evidence.take(30)
                    for (record in displayEntries) {
                        val displayCategory = record.category.lowercase().replace("_", " ")
                        val displayValue = if (record.value.length > 70) {
                            record.value.take(67) + "..."
                        } else {
                            record.value
                        }
                        row(
                            cyan(displayCategory),
                            white(displayValue),
                            green("collected")
                        )
                    }
                    if (evidence.size > 30) {
                        row(dim("..."), dim("${evidence.size - 30} more entries"), dim(""))
                    }
                    for (missing in gate.missing) {
                        val displayCategory = missing.lowercase().replace("_", " ")
                        row(
                            cyan(displayCategory),
                            dim("(not collected)"),
                            red("missing")
                        )
                    }
                }
            }
            terminal.println(t)
        } else if (gate.missing.isNotEmpty()) {
            terminal.println(yellow("Missing evidence categories:"))
            for (category in gate.missing) {
                terminal.println(yellow("  - ${category.lowercase().replace("_", " ")}"))
            }
        }

        val statusLine = if (gate.passed) {
            green("Evidence gate: PASSED (${gate.collected}/${gate.required} categories)")
        } else {
            yellow("Evidence gate: INCOMPLETE (${gate.collected}/${gate.required} categories)")
        }
        terminal.println(statusLine)
        terminal.println()
    }

    /**
     * Renders an evidence summary table from a key-value map and a list of
     * missing evidence category names. This is a convenience overload for
     * callers that have pre-processed evidence into a map.
     *
     * @param evidence map of evidence key -> value
     * @param missing  list of missing evidence category names
     */
    fun evidenceTable(evidence: Map<String, String>, missing: List<String>) {
        if (evidence.isEmpty() && missing.isEmpty()) return

        terminal.println()
        terminal.println(bold("Evidence Summary"))

        if (evidence.isNotEmpty()) {
            val t = table {
                header {
                    row(brightWhite("Category"), brightWhite("Value"))
                }
                body {
                    val displayEntries = evidence.entries.take(20)
                    for ((key, value) in displayEntries) {
                        val displayKey = key.substringBefore(":").lowercase().replace("_", " ")
                        val displayValue = if (value.length > 80) value.take(77) + "..." else value
                        row(cyan(displayKey), white(displayValue))
                    }
                    if (evidence.size > 20) {
                        row(dim("..."), dim("${evidence.size - 20} more entries"))
                    }
                }
            }
            terminal.println(t)
        }

        if (missing.isNotEmpty()) {
            terminal.println()
            terminal.println(yellow("Missing evidence categories:"))
            for (category in missing) {
                terminal.println(yellow("  - ${category.lowercase().replace("_", " ")}"))
            }
        }
        terminal.println()
    }

    // ── Result display ───────────────────────────────────────────────────────

    /**
     * Prints the final LLM response, enclosed between horizontal separators.
     * The response is printed as-is, suitable for markdown-compatible terminal
     * rendering.
     *
     * @param response the LLM response text to display
     */
    fun result(response: String) {
        terminal.println()
        separator()
        terminal.println()
        terminal.println(response)
        terminal.println()
        separator()
    }

    // ── Streaming output ─────────────────────────────────────────────────────

    /**
     * Prints a single token to the terminal without appending a newline.
     * Used during streaming LLM responses so that tokens appear incrementally.
     *
     * @param token the text fragment to print
     */
    fun streamToken(token: String) {
        terminal.print(token)
    }

    /**
     * Terminates the current streaming output by printing a newline.
     * Call this after the last [streamToken] in a streaming sequence.
     */
    fun streamEnd() {
        terminal.println()
    }

    // ── Message levels ───────────────────────────────────────────────────────

    /**
     * Prints a blue informational message.
     *
     * @param message the message text
     */
    fun info(message: String) {
        terminal.println(blue(message))
    }

    /**
     * Prints a yellow warning message prefixed with [WARN].
     *
     * @param message the warning text
     */
    fun warn(message: String) {
        terminal.println(yellow("[WARN] $message"))
    }

    /**
     * Prints a red error message prefixed with [ERROR].
     *
     * @param message the error text
     */
    fun error(message: String) {
        terminal.println(red("[ERROR] $message"))
    }

    /**
     * Prints a green success message prefixed with [OK].
     *
     * @param message the success text
     */
    fun success(message: String) {
        terminal.println(green("[OK] $message"))
    }

    // ── Separator ────────────────────────────────────────────────────────────

    /**
     * Prints a horizontal rule (dimmed line of dashes) to visually separate
     * output sections.
     */
    fun separator() {
        terminal.println(dim("─".repeat(60)))
    }

    // ── Raw text ─────────────────────────────────────────────────────────────

    /**
     * Prints raw text to the terminal. Delegates directly to [Terminal.println].
     *
     * @param text the text to print
     */
    fun println(text: String) {
        terminal.println(text)
    }

    // ── Model info ───────────────────────────────────────────────────────────

    /**
     * Prints a table of models and their assigned roles.
     *
     * @param models list of (role, modelName) pairs
     */
    fun modelInfo(models: List<Pair<String, String>>) {
        if (models.isEmpty()) {
            warn("No models configured.")
            return
        }

        terminal.println()
        terminal.println(bold("Model Configuration"))

        val t = table {
            header {
                row(brightWhite("Role"), brightWhite("Model"))
            }
            body {
                for ((role, model) in models) {
                    row(cyan(role), white(model))
                }
            }
        }
        terminal.println(t)
        terminal.println()
    }

    // ── Trace display ────────────────────────────────────────────────────────

    /**
     * Prints the execution trace as a table of stages with details and durations.
     * Only shown when [showTrace] is true.
     *
     * @param trace list of (stageName, detail, durationMs) triples
     */
    fun printTrace(trace: List<Triple<String, String, Long>>) {
        if (!showTrace || trace.isEmpty()) return

        terminal.println()
        terminal.println(dim("Execution Trace:"))

        val t = table {
            header {
                row(dim("Stage"), dim("Detail"), dim("Duration"))
            }
            body {
                for ((stageName, detail, duration) in trace) {
                    val displayDetail = if (detail.length > 60) detail.take(57) + "..." else detail
                    row(
                        cyan(stageName),
                        dim(displayDetail),
                        dim("${duration}ms")
                    )
                }
            }
        }
        terminal.println(t)
    }

    // ── REPL prompt ──────────────────────────────────────────────────────────

    /**
     * Prints the REPL prompt indicator without a trailing newline.
     */
    fun prompt() {
        terminal.print(brightCyan("forge> "))
    }

    /**
     * Prints the REPL prompt with a focused module name indicator.
     *
     * @param moduleName the name of the focused module
     */
    fun promptWithModule(moduleName: String) {
        terminal.print(brightCyan("forge[$moduleName]> "))
    }

    // ── Decomposition trace ──────────────────────────────────────────────────

    /**
     * Shows the detected prompt complexity and archetype recognition results.
     */
    fun showPromptRecognition(complexity: PromptComplexity, archetypeCount: Int) {
        if (!showTrace) return
        terminal.println(dim("    Prompt complexity: ${complexity.name} ($archetypeCount archetype(s) detected)"))
    }

    /**
     * Shows the execution plan: partitions and their layer assignments.
     */
    fun showExecutionPlan(plan: ExecutionPlan) {
        if (!showTrace) return
        terminal.println()
        terminal.println(bold("Execution Plan"))
        terminal.println(dim("  Complexity: ${plan.complexity.name} | Partitions: ${plan.partitionCount} | Layers: ${plan.executionLayers.size}"))
        terminal.println(dim("  Primary archetype: ${plan.primaryArchetype.label}"))

        for ((layerIdx, layer) in plan.executionLayers.withIndex()) {
            val partitionLabels = layer.mapNotNull { id ->
                plan.partitionById(id)?.let { "${it.id}: ${it.semanticLabel}" }
            }
            terminal.println(dim("  Layer $layerIdx: [${partitionLabels.joinToString(" | ")}]"))
        }
        terminal.println()
    }

    /**
     * Shows that a partition has started execution.
     */
    fun showPartitionStart(partition: PromptPartition) {
        if (!showTrace) return
        terminal.println(dim("    [${partition.id}] Starting: ${partition.semanticLabel} (${partition.taskType.displayName})"))
    }

    /**
     * Shows that a partition has completed.
     */
    fun showPartitionComplete(partition: PromptPartition, durationMs: Long) {
        if (!showTrace) return
        terminal.println(dim("    [${partition.id}] Completed in ${durationMs}ms"))
    }

    /**
     * Shows reconciliation results if any issues were found.
     */
    fun showReconciliation(report: ReconciliationReport) {
        if (!showTrace) return
        terminal.println()
        terminal.println(yellow("  Reconciliation: ${report.issueCount} issue(s) detected"))

        for (c in report.contradictions) {
            val text = "    [${c.severity}] ${c.description}"
            when (c.severity) {
                ContradictionSeverity.HIGH -> terminal.println(red(text))
                ContradictionSeverity.MEDIUM -> terminal.println(yellow(text))
                ContradictionSeverity.LOW -> terminal.println(dim(text))
            }
        }
        for (m in report.missingArtifacts) {
            terminal.println(yellow("    [MISSING] $m"))
        }
        for (u in report.unresolvedDeps) {
            terminal.println(yellow("    [UNRESOLVED] $u"))
        }
        terminal.println()
    }

    /**
     * Shows that synthesis is beginning.
     */
    fun showSynthesisStart() {
        if (!showTrace) return
        terminal.println(dim("    Synthesizing results from all partitions..."))
    }

    /**
     * Shows the full decomposition trace timeline.
     */
    fun showDecompositionTrace(tracer: DecompositionTracer) {
        if (!showTrace || tracer.isEmpty) return
        terminal.println()
        terminal.println(dim(tracer.summary()))
    }
}
