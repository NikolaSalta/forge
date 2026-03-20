package forge.core.prompt

import forge.core.ModelRole

/**
 * Abstraction of internal specialized worker roles. Even though FORGE is not
 * implemented as literal separate agents, each partition is conceptually handled
 * by a worker with a specific responsibility and model preference.
 *
 * Worker roles map to [ModelRole]s for model selection and carry a description
 * used in trace output for explainability.
 */
enum class WorkerRole(
    /** The preferred model role for this worker. */
    val modelRole: ModelRole,
    /** Human-readable description of this worker's responsibility. */
    val description: String
) {
    /** Deep analysis of code structure, architecture, and patterns. */
    ANALYZER(ModelRole.REASON, "Deep analysis of code structure and patterns"),
    /** Planning implementation steps, milestones, and execution order. */
    PLANNER(ModelRole.REASON, "Planning implementation steps and execution order"),
    /** Condensing large outputs into concise summaries. */
    SUMMARIZER(ModelRole.SUMMARIZE, "Condensing large outputs into summaries"),
    /** Code generation: features, patches, refactoring. */
    GENERATOR(ModelRole.CODE, "Code generation and implementation"),
    /** Validation and verification of code, tests, or artifacts. */
    VALIDATOR(ModelRole.REASON, "Validation and verification"),
    /** Code review, quality assessment, tech debt analysis. */
    REVIEWER(ModelRole.REASON, "Code review and quality audit"),
    /** Security analysis: vulnerabilities, auth, secrets, compliance. */
    SECURITY_AUDITOR(ModelRole.REASON, "Security analysis and vulnerability detection"),
    /** Test planning, coverage analysis, QA scenarios. */
    QA_TESTER(ModelRole.REASON, "Test planning and QA analysis"),
    /** Documentation writing and alignment. */
    DOCUMENTATION_WRITER(ModelRole.SUMMARIZE, "Documentation creation and alignment"),
    /** CI/CD pipeline analysis and automation. */
    DEVOPS_ANALYST(ModelRole.REASON, "CI/CD and deployment analysis"),
    /** Build system, dependency, and runtime analysis. */
    BUILD_ANALYST(ModelRole.REASON, "Build system and dependency analysis"),
    /** Performance and memory optimization analysis. */
    PERFORMANCE_ANALYST(ModelRole.REASON, "Performance and memory optimization"),
    /** Architecture review and comparison. */
    ARCHITECT(ModelRole.REASON, "Architecture review and design"),
    /** Intent classification (fast model). */
    CLASSIFIER(ModelRole.CLASSIFY, "Fast intent classification"),
    /** Repository structure scanning and evidence gathering. */
    SCANNER(ModelRole.CLASSIFY, "Repository scanning and evidence collection"),
    /** Image and diagram analysis. */
    VISION_ANALYST(ModelRole.VISION, "Image and diagram analysis"),
    /** Cross-partition result synthesis and merging. */
    SYNTHESIZER(ModelRole.REASON, "Cross-partition result synthesis");

    companion object {
        /**
         * Infer the worker role from a [PromptArchetype].
         */
        fun fromArchetype(archetype: PromptArchetype): WorkerRole = when (archetype) {
            // Analysis → ANALYZER
            PromptArchetype.ARCHITECTURE_ANALYSIS,
            PromptArchetype.REPOSITORY_OVERVIEW,
            PromptArchetype.FULL_REPO_ANALYSIS -> ANALYZER

            // Build → BUILD_ANALYST
            PromptArchetype.BUILD_SYSTEM_ANALYSIS -> BUILD_ANALYST

            // Reviews → REVIEWER / SECURITY_AUDITOR / QA_TESTER
            PromptArchetype.CODE_REVIEW -> REVIEWER
            PromptArchetype.SECURITY_REVIEW -> SECURITY_AUDITOR
            PromptArchetype.QA_REVIEW,
            PromptArchetype.TEST_COVERAGE -> QA_TESTER

            // Implementation → GENERATOR
            PromptArchetype.FEATURE_IMPLEMENTATION,
            PromptArchetype.API_DESIGN,
            PromptArchetype.FRONTEND_IMPLEMENTATION,
            PromptArchetype.BACKEND_IMPLEMENTATION,
            PromptArchetype.FULLSTACK_IMPLEMENTATION -> GENERATOR

            // Bug analysis → VALIDATOR
            PromptArchetype.BUG_ANALYSIS,
            PromptArchetype.VERIFICATION -> VALIDATOR

            // Documentation → DOCUMENTATION_WRITER
            PromptArchetype.DOCS_ALIGNMENT -> DOCUMENTATION_WRITER

            // CI/CD → DEVOPS_ANALYST
            PromptArchetype.CI_CD_ANALYSIS -> DEVOPS_ANALYST

            // Extraction & tooling → GENERATOR
            PromptArchetype.FRAMEWORK_EXTRACTION,
            PromptArchetype.TOOLING_CREATION -> GENERATOR

            // ML → GENERATOR
            PromptArchetype.ML_PIPELINE,
            PromptArchetype.MODEL_TRAINING -> GENERATOR

            // IntelliJ → GENERATOR
            PromptArchetype.PLUGIN_DEVELOPMENT,
            PromptArchetype.EXTENSION_POINT -> GENERATOR

            // Workspace & runtime
            PromptArchetype.TEMPORARY_WORKSPACE,
            PromptArchetype.WORKSPACE_ISOLATION -> ARCHITECT
            PromptArchetype.RAM_OPTIMIZATION -> PERFORMANCE_ANALYST
            PromptArchetype.TRACEABILITY_TIMELINE -> ANALYZER

            // Ollama & model
            PromptArchetype.OLLAMA_INTEGRATION,
            PromptArchetype.MODEL_ROUTING -> BUILD_ANALYST

            // Security & privacy
            PromptArchetype.SECRET_HANDLING,
            PromptArchetype.PLUGIN_PERMISSIONS,
            PromptArchetype.SUPPLY_CHAIN_DEPENDENCY,
            PromptArchetype.LOGGING_TRACE_SAFETY,
            PromptArchetype.DATA_PRIVACY,
            PromptArchetype.WHITE_HAT_SECURITY -> SECURITY_AUDITOR

            // Composite / meta
            PromptArchetype.PERFORMANCE_ANALYSIS -> PERFORMANCE_ANALYST
            PromptArchetype.DEPENDENCY_ANALYSIS -> BUILD_ANALYST
            PromptArchetype.MIGRATION_PLANNING -> PLANNER
            PromptArchetype.ONBOARDING -> SUMMARIZER
            PromptArchetype.COMPARISON -> ARCHITECT
            PromptArchetype.COMPLIANCE_CHECK -> SECURITY_AUDITOR
        }
    }
}
