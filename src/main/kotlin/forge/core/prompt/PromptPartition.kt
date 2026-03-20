package forge.core.prompt

import forge.core.TaskType

/**
 * A single decomposed partition of a user prompt. Each partition represents
 * one coherent sub-task that can be executed independently (or with declared
 * dependencies on other partitions).
 */
data class PromptPartition(
    /** Unique partition identifier, e.g. "P1", "P2". */
    val id: String,
    /** Human-readable label, e.g. "repository architecture analysis". */
    val semanticLabel: String,
    /** The matched archetype driving this partition. */
    val archetype: PromptArchetype,
    /** The resolved task type for pipeline execution. */
    val taskType: TaskType,
    /** The extracted sub-prompt text for this partition. */
    val subPrompt: String,
    /** Optional file/module/package scope constraint. */
    val targetScope: String? = null,
    /** IDs of partitions this one depends on (must complete first). */
    val dependsOn: List<String> = emptyList(),
    /** Whether this partition is safe for parallel execution. */
    val canParallel: Boolean = true,
    /** Whether failure of this partition blocks the final synthesis. */
    val isBlocking: Boolean = true,
    /** Labels of artifacts this partition produces (for downstream use). */
    val producesArtifacts: List<String> = emptyList(),
    /** Whether the result requires post-validation. */
    val requiresValidation: Boolean = false,
    /** The functional role of this partition in the decomposition. */
    val unitType: PromptUnitType = PromptUnitType.INTENT,
    /** The expected output format for this partition's LLM response. */
    val expectedOutputShape: OutputShape = OutputShape.ANALYSIS,
    /** Optional model role override (null = use default from archetype's TaskType). */
    val modelRole: String? = null
)
