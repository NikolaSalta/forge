package forge.core.prompt

/**
 * Builds an [ExecutionPlan] from analyzed partitions by constructing a
 * dependency DAG and performing topological sort into execution layers.
 *
 * Layers are groups of partitions that can safely run in parallel.
 * Layer N+1 only starts after all partitions in layer N have completed.
 */
class ExecutionPlanner(
    private val tracer: DecompositionTracer? = null
) {

    /**
     * Build an execution plan from the analysis result.
     */
    fun buildPlan(
        prompt: String,
        analysisResult: AnalysisResult
    ): ExecutionPlan {
        val partitions = analysisResult.partitions
        val layers = topologicalSort(partitions)

        tracer?.record(
            TraceEventType.DEPENDENCY_GRAPH_BUILT,
            "${layers.size} execution layer(s) built from ${partitions.size} partition(s)"
        )

        return ExecutionPlan(
            originalPrompt = prompt,
            complexity = analysisResult.complexity,
            partitions = partitions,
            executionLayers = layers,
            primaryArchetype = analysisResult.primaryArchetype,
            secondaryArchetypes = analysisResult.secondaryArchetypes
        )
    }

    /**
     * Topological sort of partitions into execution layers.
     *
     * - Layer 0: partitions with no dependencies (run in parallel).
     * - Layer 1: partitions whose dependencies are all in layer 0.
     * - Layer N: partitions whose dependencies are all in layers 0..N-1.
     *
     * Partitions that form dependency cycles are placed in the last layer.
     */
    private fun topologicalSort(partitions: List<PromptPartition>): List<List<String>> {
        if (partitions.isEmpty()) return emptyList()
        if (partitions.size == 1) return listOf(listOf(partitions.first().id))

        val allIds = partitions.map { it.id }.toSet()
        val dependencyMap = partitions.associate { p ->
            // Filter out dependencies that reference non-existent partitions
            p.id to p.dependsOn.filter { it in allIds }.toSet()
        }

        val layers = mutableListOf<List<String>>()
        val assigned = mutableSetOf<String>()
        var remaining = allIds.toMutableSet()

        // Kahn's algorithm variant: assign to layers iteratively
        while (remaining.isNotEmpty()) {
            // Find partitions whose dependencies are all already assigned
            val ready = remaining.filter { id ->
                val deps = dependencyMap[id] ?: emptySet()
                deps.all { it in assigned }
            }

            if (ready.isEmpty()) {
                // Cycle detected — dump all remaining into the last layer
                layers.add(remaining.toList())
                break
            }

            layers.add(ready)
            assigned.addAll(ready)
            remaining.removeAll(ready.toSet())
        }

        return layers
    }
}
