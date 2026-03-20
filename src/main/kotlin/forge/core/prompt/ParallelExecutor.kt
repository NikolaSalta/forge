package forge.core.prompt

import forge.core.ForgeResult
import forge.core.Orchestrator
import forge.core.StateManager
import forge.core.StoppedException
import forge.ui.ForgeConsole
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.nio.file.Path

/**
 * Executes an [ExecutionPlan] by running partitions in parallel within each
 * execution layer, and layers sequentially.
 *
 * Each partition is executed through the existing [Orchestrator.execute] pipeline,
 * reusing all scan/chunk caching. A [Semaphore] limits concurrent LLM calls to
 * avoid overwhelming Ollama.
 */
class ParallelExecutor(
    private val orchestrator: Orchestrator,
    private val stateManager: StateManager,
    private val console: ForgeConsole,
    private val maxConcurrentLlmCalls: Int = 3,
    private val tracer: DecompositionTracer? = null
) {
    private val llmSemaphore = Semaphore(maxConcurrentLlmCalls)

    /**
     * Execute all partitions in the plan according to their layer ordering.
     *
     * @return map of partition ID to its result
     */
    suspend fun execute(
        plan: ExecutionPlan,
        repoPath: Path,
        attachedFiles: List<Path> = emptyList()
    ): Map<String, PartitionResult> {
        val results = mutableMapOf<String, PartitionResult>()

        for ((layerIndex, layer) in plan.executionLayers.withIndex()) {
            stateManager.checkPauseOrStop()

            val partitions = layer.mapNotNull { plan.partitionById(it) }

            console.traceInfo("Layer $layerIndex: executing ${partitions.size} partition(s) in parallel")
            tracer?.record(TraceEventType.EXECUTION_LAYER_STARTED, "Layer $layerIndex: ${partitions.size} partition(s)")

            val layerResults = executeLayer(partitions, results, repoPath, attachedFiles)
            results.putAll(layerResults)
        }

        return results
    }

    /**
     * Execute all partitions in a single layer concurrently.
     */
    private suspend fun executeLayer(
        partitions: List<PromptPartition>,
        previousResults: Map<String, PartitionResult>,
        repoPath: Path,
        attachedFiles: List<Path>
    ): Map<String, PartitionResult> = coroutineScope {
        val deferred = partitions.map { partition ->
            async {
                partition.id to executeSinglePartition(partition, previousResults, repoPath, attachedFiles)
            }
        }
        deferred.awaitAll().toMap()
    }

    /**
     * Execute a single partition through the Orchestrator pipeline.
     * Uses the semaphore to limit concurrent LLM calls.
     */
    private suspend fun executeSinglePartition(
        partition: PromptPartition,
        previousResults: Map<String, PartitionResult>,
        repoPath: Path,
        attachedFiles: List<Path>
    ): PartitionResult {
        console.showPartitionStart(partition)
        tracer?.record(TraceEventType.PARTITION_STARTED, partition.semanticLabel, partitionId = partition.id)
        val startTime = System.currentTimeMillis()

        return try {
            // Check if any blocking dependency failed
            for (depId in partition.dependsOn) {
                val depResult = previousResults[depId]
                if (depResult != null && depResult.status == PartitionStatus.FAILED) {
                    console.traceInfo("  Partition ${partition.id} skipped — dependency $depId failed")
                    tracer?.record(TraceEventType.PARTITION_SKIPPED, "Dependency $depId failed", partitionId = partition.id)
                    return PartitionResult(
                        partitionId = partition.id,
                        status = PartitionStatus.SKIPPED,
                        durationMs = System.currentTimeMillis() - startTime,
                        error = "Dependency $depId failed"
                    )
                }
            }

            // Build structured context pack for this partition
            val contextPack = buildContextPack(partition, previousResults)

            // Execute through the orchestrator with semaphore limiting LLM concurrency
            val result: ForgeResult = llmSemaphore.withPermit {
                orchestrator.execute(
                    userInput = contextPack.buildPrompt(),
                    repoPath = repoPath,
                    attachedFiles = attachedFiles
                )
            }

            val duration = System.currentTimeMillis() - startTime
            console.showPartitionComplete(partition, duration)
            tracer?.record(TraceEventType.PARTITION_COMPLETED, "Completed: ${partition.semanticLabel}", partitionId = partition.id, durationMs = duration)

            PartitionResult(
                partitionId = partition.id,
                status = PartitionStatus.COMPLETED,
                response = result.response,
                durationMs = duration,
                modelUsed = result.model
            )
        } catch (e: StoppedException) {
            throw e // Propagate stop signal
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            console.traceInfo("  Partition ${partition.id} FAILED: ${e.message}")
            tracer?.record(TraceEventType.PARTITION_FAILED, "Failed: ${e.message}", partitionId = partition.id, durationMs = duration)

            PartitionResult(
                partitionId = partition.id,
                status = PartitionStatus.FAILED,
                durationMs = duration,
                error = e.message
            )
        }
    }

    /**
     * Build a structured [ContextPack] for a partition, including output shape
     * instructions and upstream dependency context.
     */
    private fun buildContextPack(
        partition: PromptPartition,
        previousResults: Map<String, PartitionResult>
    ): ContextPack {
        // Build upstream context from dependencies
        val upstreamContext = if (partition.dependsOn.isEmpty()) {
            null
        } else {
            val depContext = partition.dependsOn.mapNotNull { depId ->
                val depResult = previousResults[depId] ?: return@mapNotNull null
                if (depResult.response != null) {
                    "--- Context from previous analysis ($depId) ---\n${depResult.response.take(2000)}"
                } else null
            }
            if (depContext.isNotEmpty()) depContext.joinToString("\n\n") else null
        }

        return ContextPack(
            partitionId = partition.id,
            subtask = partition.subPrompt,
            expectedOutputShape = partition.expectedOutputShape,
            targetScope = partition.targetScope,
            modelRole = partition.modelRole,
            upstreamContext = upstreamContext
        )
    }
}
