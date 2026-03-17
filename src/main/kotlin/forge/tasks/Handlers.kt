package forge.tasks

import forge.ForgeConfig
import forge.core.TaskType

// ═══════════════════════════════════════════════════════════════════════════════
//  1. RepoAnalysisHandler
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Handles full repository analysis: structure, tech stack, architecture,
 * key components, and build system.
 */
class RepoAnalysisHandler(config: ForgeConfig) : TaskHandler(config) {

    override val taskType: TaskType = TaskType.REPO_ANALYSIS

    override fun buildSpecificPrompt(
        evidence: Map<String, String>,
        chunks: List<String>
    ): String = buildString {
        appendLine("# Repository Analysis")
        appendLine()
        appendLine(instructionBlock(
            "Provide a comprehensive analysis of this repository.",
            "Cover the overall structure, tech stack, architecture patterns, and key components.",
            "Identify the build system, primary languages, and deployment setup.",
            "Highlight any notable design decisions, strengths, and potential concerns.",
            "Use clear section headings and markdown formatting.",
            "Include a summary section at the top with the most important findings."
        ))
        appendLine()
        appendLine(formatEvidenceSection(evidence, "BUILD_SYSTEM", "Build System"))
        appendLine(formatEvidenceSection(evidence, "LANGUAGES", "Languages"))
        appendLine(formatEvidenceSection(evidence, "SOURCE_ROOTS", "Source Roots"))
        appendLine(formatEvidenceSection(evidence, "KEY_MODULES", "Key Modules"))
        appendLine(formatEvidenceSection(evidence, "DEPENDENCIES", "Dependencies"))
        appendLine(formatEvidenceSection(evidence, "CI_CD_SIGNALS", "CI/CD"))
        appendLine(formatEvidenceSection(evidence, "TEST_ROOTS", "Test Roots"))
        appendLine(formatEvidenceSection(evidence, "ARCHITECTURE", "Architecture"))
        appendLine(formatEvidenceSection(evidence, "RUNTIME_SHAPE", "Runtime Shape"))
        appendLine()
        appendLine("## Relevant Code Samples")
        appendLine(formatChunks(chunks))
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  2. ArchitectureHandler
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Handles architecture review: patterns, layering, module boundaries,
 * dependency flow, and design decisions.
 */
class ArchitectureHandler(config: ForgeConfig) : TaskHandler(config) {

    override val taskType: TaskType = TaskType.ARCHITECTURE_REVIEW

    override fun buildSpecificPrompt(
        evidence: Map<String, String>,
        chunks: List<String>
    ): String = buildString {
        appendLine("# Architecture Review")
        appendLine()
        appendLine(instructionBlock(
            "Review the software architecture of this project in depth.",
            "Identify architectural patterns in use (MVC, Clean Architecture, Hexagonal, etc.).",
            "Analyze module boundaries, dependency flow, and separation of concerns.",
            "Evaluate the layering: are layers well-separated or tightly coupled?",
            "Assess API boundaries between modules.",
            "Identify potential architectural improvements or risks.",
            "Provide a dependency diagram description if the structure is clear enough.",
            "Rate the overall architectural health on a scale of 1-10 with justification."
        ))
        appendLine()
        appendLine(formatEvidenceSection(evidence, "ARCHITECTURE", "Detected Architecture Patterns"))
        appendLine(formatEvidenceSection(evidence, "MODULE_MAP", "Module Map"))
        appendLine(formatEvidenceSection(evidence, "KEY_MODULES", "Key Modules"))
        appendLine(formatEvidenceSection(evidence, "DEPENDENCIES", "Dependencies"))
        appendLine(formatEvidenceSection(evidence, "SOURCE_ROOTS", "Source Roots"))
        appendLine(formatEvidenceSection(evidence, "INTEGRATION_POINTS", "Integration Points"))
        appendLine()
        appendLine("## Relevant Code Samples")
        appendLine(formatChunks(chunks))
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  3. CodeQualityHandler
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Handles code quality review: patterns, anti-patterns, tech debt,
 * naming conventions, and maintainability.
 */
class CodeQualityHandler(config: ForgeConfig) : TaskHandler(config) {

    override val taskType: TaskType = TaskType.CODE_QUALITY_REVIEW

    override fun buildSpecificPrompt(
        evidence: Map<String, String>,
        chunks: List<String>
    ): String = buildString {
        appendLine("# Code Quality Review")
        appendLine()
        appendLine(instructionBlock(
            "Perform a thorough code quality assessment.",
            "Evaluate naming conventions and consistency across the codebase.",
            "Identify code smells, anti-patterns, and technical debt.",
            "Assess error handling practices and robustness.",
            "Check for proper use of language-specific idioms and best practices.",
            "Look for duplicate code or missed abstraction opportunities.",
            "Evaluate documentation quality: comments, docstrings, API docs.",
            "Assess testability of the code structure.",
            "Provide specific, actionable recommendations with code examples.",
            "Categorize findings by severity: Critical, Major, Minor, Suggestion."
        ))
        appendLine()
        appendLine(formatEvidenceSection(evidence, "CONVENTIONS", "Conventions"))
        appendLine(formatEvidenceSection(evidence, "LANGUAGES", "Languages"))
        appendLine(formatEvidenceSection(evidence, "TEST_PATTERNS", "Test Patterns"))
        appendLine(formatEvidenceSection(evidence, "KEY_FILES", "Key Files"))
        appendLine()
        appendLine("## Code Samples to Review")
        appendLine(formatChunks(chunks, maxChunks = 20))
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  4. SecurityHandler
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Handles security review: vulnerabilities, dependency issues,
 * authentication patterns, and data handling.
 */
class SecurityHandler(config: ForgeConfig) : TaskHandler(config) {

    override val taskType: TaskType = TaskType.SECURITY_REVIEW

    override fun buildSpecificPrompt(
        evidence: Map<String, String>,
        chunks: List<String>
    ): String = buildString {
        appendLine("# Security Review")
        appendLine()
        appendLine(instructionBlock(
            "Conduct a security audit of this codebase.",
            "Identify potential vulnerabilities: injection, XSS, CSRF, auth bypass, etc.",
            "Review authentication and authorization patterns for weaknesses.",
            "Check for hardcoded secrets, credentials, or API keys.",
            "Assess input validation and sanitization practices.",
            "Review dependency security: known vulnerable libraries or outdated versions.",
            "Evaluate data handling: encryption at rest/transit, PII exposure.",
            "Check for proper error handling that does not leak sensitive information.",
            "Categorize findings by OWASP Top 10 category where applicable.",
            "Provide a risk rating (Critical/High/Medium/Low) for each finding.",
            "Include remediation recommendations with code examples."
        ))
        appendLine()
        appendLine(formatEvidenceSection(evidence, "AUTH_PATTERNS", "Authentication Patterns"))
        appendLine(formatEvidenceSection(evidence, "API_ENDPOINTS", "API Endpoints"))
        appendLine(formatEvidenceSection(evidence, "DEPENDENCIES", "Dependencies"))
        appendLine(formatEvidenceSection(evidence, "CONFIG_FILES", "Configuration Files"))
        appendLine(formatEvidenceSection(evidence, "BUILD_SYSTEM", "Build System"))
        appendLine()
        appendLine("## Code Samples to Audit")
        appendLine(formatChunks(chunks, maxChunks = 20))
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  5. ImplementHandler
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Handles feature implementation: generates complete, working code
 * that follows the project's conventions and integrates with existing architecture.
 */
class ImplementHandler(config: ForgeConfig) : TaskHandler(config) {

    override val taskType: TaskType = TaskType.IMPLEMENT_FEATURE

    override fun buildSpecificPrompt(
        evidence: Map<String, String>,
        chunks: List<String>
    ): String = buildString {
        appendLine("# Feature Implementation")
        appendLine()
        appendLine(instructionBlock(
            "Generate complete, production-ready code for the requested feature.",
            "Follow the existing project conventions, naming patterns, and architecture.",
            "Use the same language, framework, and libraries already in the project.",
            "Include proper error handling, logging, and input validation.",
            "Add documentation comments (KDoc, JSDoc, docstrings, etc.) for public APIs.",
            "If tests are expected by the project conventions, include test code.",
            "Provide file paths for where each code block should be placed.",
            "Explain your implementation decisions and trade-offs.",
            "If the feature requires multiple files, provide all of them.",
            "Ensure the code compiles and integrates with the existing codebase."
        ))
        appendLine()
        appendLine(formatEvidenceSection(evidence, "CONVENTIONS", "Project Conventions"))
        appendLine(formatEvidenceSection(evidence, "LANGUAGES", "Languages"))
        appendLine(formatEvidenceSection(evidence, "BUILD_SYSTEM", "Build System"))
        appendLine(formatEvidenceSection(evidence, "KEY_MODULES", "Key Modules"))
        appendLine(formatEvidenceSection(evidence, "REPRESENTATIVE_FILES", "Representative Files"))
        appendLine(formatEvidenceSection(evidence, "SOURCE_ROOTS", "Source Roots"))
        appendLine()
        appendLine("## Existing Code for Reference")
        appendLine(formatChunks(chunks))
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  6. BugAnalysisHandler
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Handles bug analysis: diagnosis, root cause identification,
 * reproduction steps, and fix recommendations.
 */
class BugAnalysisHandler(config: ForgeConfig) : TaskHandler(config) {

    override val taskType: TaskType = TaskType.BUG_ANALYSIS

    override fun buildSpecificPrompt(
        evidence: Map<String, String>,
        chunks: List<String>
    ): String = buildString {
        appendLine("# Bug Analysis")
        appendLine()
        appendLine(instructionBlock(
            "Analyze the described bug or issue thoroughly.",
            "Identify the most likely root cause based on the code and evidence.",
            "Trace the execution flow that leads to the bug.",
            "Explain why the bug occurs, not just what happens.",
            "Suggest a fix with complete code changes, not just descriptions.",
            "Consider edge cases and whether the fix might introduce regressions.",
            "If there are related issues or similar patterns elsewhere, flag them.",
            "Provide steps to verify the fix works correctly.",
            "Rate the severity: Critical, High, Medium, Low.",
            "If the root cause is unclear, provide a diagnostic plan with specific steps."
        ))
        appendLine()
        appendLine(formatEvidenceSection(evidence, "BUILD_SYSTEM", "Build System"))
        appendLine(formatEvidenceSection(evidence, "LANGUAGES", "Languages"))
        appendLine(formatEvidenceSection(evidence, "DEPENDENCIES", "Dependencies"))
        appendLine(formatEvidenceSection(evidence, "TEST_PATTERNS", "Test Patterns"))
        appendLine(formatEvidenceSection(evidence, "SOURCE_ROOTS", "Source Roots"))
        appendLine()
        appendLine("## Relevant Code Context")
        appendLine(formatChunks(chunks, maxChunks = 20))
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  Handler Registry
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Creates and returns all available task handlers, keyed by their [TaskType].
 * This registry allows the pipeline or CLI to look up the appropriate handler
 * for a given resolved task type.
 */
fun createHandlerRegistry(config: ForgeConfig): Map<TaskType, TaskHandler> {
    val handlers = listOf(
        RepoAnalysisHandler(config),
        ArchitectureHandler(config),
        CodeQualityHandler(config),
        SecurityHandler(config),
        ImplementHandler(config),
        BugAnalysisHandler(config)
    )
    return handlers.associateBy { it.taskType }
}
