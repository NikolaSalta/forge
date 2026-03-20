package forge.core.prompt

import forge.ForgeConfig
import forge.core.TaskType
import forge.llm.ChatMessage
import forge.llm.OllamaClient
import forge.llm.ResponseParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The result of analyzing a user prompt: detected complexity and partitions.
 */
data class AnalysisResult(
    val complexity: PromptComplexity,
    val partitions: List<PromptPartition>,
    val primaryArchetype: PromptArchetype,
    val secondaryArchetypes: List<PromptArchetype>
)

/**
 * Analyzes incoming user prompts and decomposes them into executable partitions.
 *
 * Two-layer recognition:
 * 1. **Heuristic** — keyword matching against [PromptArchetype] entries (fast, no LLM).
 * 2. **LLM decomposition** — only invoked for COMPOUND or MULTI_STAGE prompts that
 *    need fine-grained sub-prompt extraction.
 *
 * For SIMPLE prompts (single archetype match), returns a single partition and
 * skips the LLM decomposition call entirely.
 */
class PromptAnalyzer(
    private val config: ForgeConfig,
    private val ollamaClient: OllamaClient,
    private val tracer: DecompositionTracer? = null
) {
    private val lenientJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // Conjunctions that indicate multi-intent prompts
    private val multiIntentMarkers = listOf(
        " and also ", " and then ", " additionally ", " furthermore ",
        " plus ", " as well as ", " then ", " after that ",
        ", also ", "; also ", "; then "
    )

    /**
     * Analyze a raw user prompt and produce an [AnalysisResult] with partitions.
     */
    suspend fun analyze(rawPrompt: String): AnalysisResult {
        tracer?.record(TraceEventType.PROMPT_INTAKE, "Raw prompt received (${rawPrompt.length} chars)")

        // Stage 1: Heuristic archetype detection
        val scored = PromptArchetype.score(rawPrompt)
        tracer?.record(TraceEventType.PROMPT_RECOGNIZED, "${scored.size} archetype(s) matched")

        if (scored.isEmpty()) {
            // No archetype matched — treat as a simple repo analysis
            return singlePartitionResult(rawPrompt, PromptArchetype.FULL_REPO_ANALYSIS)
        }

        // Stage 2: Determine complexity
        val distinctArchetypes = scored.map { it.archetype }.distinct()
        val hasMultiIntentMarkers = multiIntentMarkers.any { rawPrompt.lowercase().contains(it) }
        val commaSegments = rawPrompt.split(",").filter { it.trim().length > 10 }

        val complexity = when {
            distinctArchetypes.size == 1 && !hasMultiIntentMarkers -> PromptComplexity.SIMPLE
            distinctArchetypes.size <= 3 -> PromptComplexity.COMPOUND
            else -> PromptComplexity.MULTI_STAGE
        }
        tracer?.record(TraceEventType.COMPLEXITY_DETECTED, "Complexity: ${complexity.name} (${distinctArchetypes.size} distinct archetype(s))")

        // Stage 3: Segmentation
        if (complexity == PromptComplexity.SIMPLE) {
            return singlePartitionResult(rawPrompt, scored.first().archetype)
        }

        // For compound/multi-stage: try LLM decomposition, fall back to heuristic split
        val decompositionConfig = config.decomposition
        val partitions = if (decompositionConfig.heuristicOnly) {
            heuristicSplit(rawPrompt, distinctArchetypes)
        } else {
            try {
                llmDecompose(rawPrompt, distinctArchetypes)
            } catch (_: Exception) {
                heuristicSplit(rawPrompt, distinctArchetypes)
            }
        }

        // Cap partition count
        val capped = partitions.take(decompositionConfig.maxPartitions)
        tracer?.record(TraceEventType.PARTITIONS_CREATED, "${capped.size} partition(s) created")

        return AnalysisResult(
            complexity = complexity,
            partitions = capped,
            primaryArchetype = scored.first().archetype,
            secondaryArchetypes = distinctArchetypes.drop(1)
        )
    }

    // ── Heuristic split ──────────────────────────────────────────────────────

    /**
     * Creates one partition per distinct archetype using the full prompt as sub-prompt.
     * This is the fast fallback when LLM decomposition is disabled or fails.
     */
    private fun heuristicSplit(
        prompt: String,
        archetypes: List<PromptArchetype>
    ): List<PromptPartition> {
        return archetypes.mapIndexed { index, archetype ->
            PromptPartition(
                id = "P${index + 1}",
                semanticLabel = archetype.label.lowercase(),
                archetype = archetype,
                taskType = archetype.primaryTaskType,
                subPrompt = prompt,
                canParallel = archetype.canParallel,
                isBlocking = true,
                unitType = PromptUnitType.fromArchetype(archetype),
                expectedOutputShape = archetype.defaultOutputShape
            )
        }
    }

    // ── LLM decomposition ────────────────────────────────────────────────────

    /**
     * Uses the classify model to decompose a complex prompt into partitions
     * with explicit sub-prompts and dependency declarations.
     */
    private suspend fun llmDecompose(
        prompt: String,
        detectedArchetypes: List<PromptArchetype>
    ): List<PromptPartition> {
        val taskTypeList = TaskType.entries.joinToString(", ") { it.name }
        val archetypeHints = detectedArchetypes.joinToString(", ") { it.label }

        val unitTypeList = PromptUnitType.entries.joinToString(", ") { it.name }

        val systemMessage = """You are a prompt decomposition engine. Given a user prompt, split it into independent task partitions.

For each partition, return a JSON object with these fields:
- "semantic_label": short description of what this partition does
- "task_type": one of [$taskTypeList]
- "sub_prompt": the extracted sub-instruction for this partition
- "depends_on": array of partition IDs (e.g. ["P1"]) this depends on, or empty array
- "can_parallel": boolean, whether this can run alongside other partitions
- "unit_type": one of [$unitTypeList] — the functional role of this partition

Detected intent hints: $archetypeHints

Return a JSON array of partition objects. Partition IDs are P1, P2, P3, etc."""

        val userMessage = "Decompose this prompt into task partitions:\n\n$prompt"

        val messages = listOf(
            ChatMessage(role = "system", content = systemMessage),
            ChatMessage(role = "user", content = userMessage)
        )

        val rawResponse = ollamaClient.chat(config.models.classify, messages)
        return parseDecompositionResponse(rawResponse, prompt, detectedArchetypes)
    }

    /**
     * Parses the LLM decomposition response (JSON array) into PromptPartitions.
     */
    private fun parseDecompositionResponse(
        rawResponse: String,
        originalPrompt: String,
        fallbackArchetypes: List<PromptArchetype>
    ): List<PromptPartition> {
        val jsonString = ResponseParser.extractJson(rawResponse)
            ?: return heuristicSplit(originalPrompt, fallbackArchetypes)

        return try {
            val jsonArray = lenientJson.parseToJsonElement(jsonString) as? JsonArray
                ?: return heuristicSplit(originalPrompt, fallbackArchetypes)

            jsonArray.mapIndexed { index, element ->
                val obj = element as? JsonObject
                    ?: return@mapIndexed null

                val semanticLabel = obj["semantic_label"]?.jsonPrimitive?.content
                    ?: "partition ${index + 1}"
                val taskTypeName = obj["task_type"]?.jsonPrimitive?.content ?: "REPO_ANALYSIS"
                val subPrompt = obj["sub_prompt"]?.jsonPrimitive?.content ?: originalPrompt
                val dependsOn = try {
                    obj["depends_on"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                } catch (_: Exception) {
                    emptyList()
                }
                val canParallel = try {
                    obj["can_parallel"]?.jsonPrimitive?.boolean ?: true
                } catch (_: Exception) {
                    true
                }

                val taskType = TaskType.fromString(taskTypeName) ?: TaskType.REPO_ANALYSIS
                val archetype = fallbackArchetypes.getOrNull(index)
                    ?: PromptArchetype.entries.find { it.primaryTaskType == taskType }
                    ?: PromptArchetype.FULL_REPO_ANALYSIS

                // Parse unit_type from LLM response, fall back to archetype-based inference
                val unitTypeName = obj["unit_type"]?.jsonPrimitive?.content
                val unitType = if (unitTypeName != null) {
                    PromptUnitType.fromString(unitTypeName)
                } else {
                    PromptUnitType.fromArchetype(archetype)
                }

                PromptPartition(
                    id = "P${index + 1}",
                    semanticLabel = semanticLabel,
                    archetype = archetype,
                    taskType = taskType,
                    subPrompt = subPrompt,
                    dependsOn = dependsOn,
                    canParallel = canParallel,
                    isBlocking = true,
                    unitType = unitType,
                    expectedOutputShape = archetype.defaultOutputShape
                )
            }.filterNotNull()
        } catch (_: Exception) {
            heuristicSplit(originalPrompt, fallbackArchetypes)
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun singlePartitionResult(prompt: String, archetype: PromptArchetype): AnalysisResult {
        val partition = PromptPartition(
            id = "P1",
            semanticLabel = archetype.label.lowercase(),
            archetype = archetype,
            taskType = archetype.primaryTaskType,
            subPrompt = prompt,
            canParallel = archetype.canParallel,
            isBlocking = true,
            unitType = PromptUnitType.fromArchetype(archetype),
            expectedOutputShape = archetype.defaultOutputShape
        )
        return AnalysisResult(
            complexity = PromptComplexity.SIMPLE,
            partitions = listOf(partition),
            primaryArchetype = archetype,
            secondaryArchetypes = emptyList()
        )
    }
}
