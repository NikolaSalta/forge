package forge.core

import forge.ForgeConfig
import forge.llm.ChatMessage
import forge.llm.OllamaClient
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Plan data stored in memory during the approval loop.
 */
data class PlanState(
    val id: String,
    val originalQuery: String,
    var simplePlan: String,
    var implementationPlan: String? = null,
    var phase: PlanPhase = PlanPhase.SIMPLE_PLAN,
    var revisions: Int = 0
)

enum class PlanPhase {
    SIMPLE_PLAN,
    SIMPLE_APPROVED,
    IMPLEMENTATION_PLAN,
    IMPLEMENTATION_APPROVED,
    EXECUTING,
    DONE
}

/**
 * Generates and refines plans using the Reason model.
 * Plans go through a two-stage approval loop:
 *   1. Simple plan → user approval
 *   2. Implementation plan → user approval
 *   3. Execute with approved plan as instruction
 */
class PlanService(
    private val ollama: OllamaClient,
    private val config: ForgeConfig
) {
    private val activePlans = ConcurrentHashMap<String, PlanState>()

    fun getPlan(planId: String): PlanState? = activePlans[planId]

    /**
     * Generate a simple, high-level plan from the user's prompt.
     */
    suspend fun generateSimplePlan(query: String, repoSummary: String): PlanState {
        val planId = "plan-${UUID.randomUUID().toString().take(8)}"

        // Truncate repo summary if too long to avoid timeout
        val truncatedSummary = if (repoSummary.length > 8000) repoSummary.take(8000) + "\n..." else repoSummary

        val messages = listOf(
            ChatMessage("system", """You are a software architect assistant.
The user has a request about a code repository. Your job is to convert their request into a clear, simple, numbered plan.

CRITICAL RULES:
- Write 8-12 numbered steps
- Each step must be grounded in the ACTUAL repository structure shown below
- Reference REAL directories, files, classes, and modules from the repository context
- Do NOT invent files, classes, or modules that don't exist in the repository
- If the repository is a collection of examples/demos, say so explicitly
- Do NOT assume the repository is something it's not
- Include a testing strategy step
- Include a migration/transformation step if applicable
- Do NOT write code — only describe what needs to be done

REPOSITORY STRUCTURE (from project index — these are REAL files and classes):
$truncatedSummary"""),
            ChatMessage("user", "Convert this request into a step-by-step plan:\n\n$query")
        )

        val response = ollama.chat(config.models.reason, messages)
        val plan = PlanState(
            id = planId,
            originalQuery = query,
            simplePlan = response.trim()
        )
        activePlans[planId] = plan
        return plan
    }

    /**
     * Refine a plan based on user feedback.
     */
    suspend fun refinePlan(planId: String, feedback: String): PlanState? {
        val plan = activePlans[planId] ?: return null

        val messages = listOf(
            ChatMessage("system", """You are a software architect assistant.
The user reviewed a plan and wants changes. Update the plan based on their feedback.

Rules:
- Keep the same numbered format
- Incorporate ALL user feedback
- Keep it simple and clear (3-8 steps)
- Do NOT write code"""),
            ChatMessage("user", """Current plan:
${plan.simplePlan}

User feedback:
$feedback

Write the updated plan:""")
        )

        val response = ollama.chat(config.models.reason, messages)
        plan.simplePlan = response.trim()
        plan.revisions++
        return plan
    }

    /**
     * Generate a detailed implementation plan from the approved simple plan.
     * Uses index context to reference real files and classes.
     */
    suspend fun generateImplementationPlan(planId: String, indexContext: String): PlanState? {
        val plan = activePlans[planId] ?: return null
        plan.phase = PlanPhase.IMPLEMENTATION_PLAN

        // Truncate index context if too long
        val truncatedContext = if (indexContext.length > 8000) indexContext.take(8000) + "\n..." else indexContext

        val messages = listOf(
            ChatMessage("system", """You are a senior software engineer creating a detailed implementation plan.
Given an approved high-level plan and REAL project structure, create a detailed implementation plan.

CRITICAL RULES:
- For each step, specify which REAL files to modify or create (use actual paths from the project)
- Reference REAL class names, method names from the project index below
- Do NOT invent classes or files that don't exist — only reference what's actually in the index
- If a new file needs to be created, say so explicitly and explain where it goes
- Include the order of changes (what depends on what)
- Note any risks or decisions that need attention
- Include a verification/testing step for each major change
- Write specific pseudocode for complex changes

REAL PROJECT STRUCTURE (from index — these files and classes actually exist):
$truncatedContext"""),
            ChatMessage("user", """Original request: ${plan.originalQuery}

Approved plan:
${plan.simplePlan}

Create a detailed implementation plan:""")
        )

        val response = ollama.chat(config.models.reason, messages)
        plan.implementationPlan = response.trim()
        return plan
    }

    /**
     * Refine the implementation plan based on user feedback.
     */
    suspend fun refineImplementationPlan(planId: String, feedback: String): PlanState? {
        val plan = activePlans[planId] ?: return null

        val messages = listOf(
            ChatMessage("system", """You are a senior software engineer updating an implementation plan.
Incorporate the user's feedback into the existing plan.

Rules:
- Keep the detailed format with file references
- Address ALL feedback points
- Maintain the verification steps"""),
            ChatMessage("user", """Current implementation plan:
${plan.implementationPlan}

User feedback:
$feedback

Write the updated implementation plan:""")
        )

        val response = ollama.chat(config.models.reason, messages)
        plan.implementationPlan = response.trim()
        plan.revisions++
        return plan
    }

    /**
     * Mark a plan as approved and ready for execution.
     */
    fun approveSimplePlan(planId: String): Boolean {
        val plan = activePlans[planId] ?: return false
        plan.phase = PlanPhase.SIMPLE_APPROVED
        return true
    }

    fun approveImplementationPlan(planId: String): Boolean {
        val plan = activePlans[planId] ?: return false
        plan.phase = PlanPhase.IMPLEMENTATION_APPROVED
        return true
    }

    /**
     * Build the execution prompt from the approved plan.
     * This replaces the raw user query with a plan-driven instruction.
     */
    fun buildExecutionPrompt(planId: String): String? {
        val plan = activePlans[planId] ?: return null
        if (plan.phase != PlanPhase.IMPLEMENTATION_APPROVED) return null

        return """Execute the following approved plan for the repository.

ORIGINAL REQUEST: ${plan.originalQuery}

APPROVED PLAN:
${plan.simplePlan}

IMPLEMENTATION PLAN:
${plan.implementationPlan}

Follow the implementation plan step by step. Reference real files and code from the project."""
    }

    fun removePlan(planId: String) {
        activePlans.remove(planId)
    }
}
