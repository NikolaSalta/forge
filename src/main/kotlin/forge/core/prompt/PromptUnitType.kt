package forge.core.prompt

/**
 * Classifies a [PromptPartition] by its functional role in the decomposition.
 * Used to determine execution strategy, validation requirements, and
 * which internal worker role should handle the partition.
 */
enum class PromptUnitType {
    /** The partition captures a user intent or goal. */
    INTENT,
    /** The partition targets a specific technical domain (backend, frontend, infra). */
    DOMAIN,
    /** The partition scopes to a specific repository, module, or file set. */
    REPOSITORY,
    /** The partition gathers evidence or facts from the codebase. */
    EVIDENCE,
    /** The partition generates code, configs, or other artifacts. */
    GENERATION,
    /** The partition validates or verifies a prior result. */
    VALIDATION,
    /** The partition performs a review or audit (code, security, QA). */
    REVIEW,
    /** The partition produces or updates documentation. */
    DOCUMENTATION,
    /** The partition creates, runs, or plans tests. */
    TESTING,
    /** The partition analyzes runtime, deployment, or CI/CD concerns. */
    RUNTIME,
    /** The partition analyzes memory, performance, or resource usage. */
    MEMORY;

    companion object {
        /**
         * Infer the unit type from a [PromptArchetype].
         * This is the heuristic mapping used when LLM decomposition is not available.
         */
        fun fromArchetype(archetype: PromptArchetype): PromptUnitType = when (archetype) {
            // Analysis archetypes → EVIDENCE
            PromptArchetype.ARCHITECTURE_ANALYSIS,
            PromptArchetype.REPOSITORY_OVERVIEW,
            PromptArchetype.BUILD_SYSTEM_ANALYSIS,
            PromptArchetype.FULL_REPO_ANALYSIS -> EVIDENCE

            // Review archetypes → REVIEW
            PromptArchetype.CODE_REVIEW,
            PromptArchetype.SECURITY_REVIEW,
            PromptArchetype.QA_REVIEW,
            PromptArchetype.TEST_COVERAGE -> REVIEW

            // Implementation archetypes → GENERATION
            PromptArchetype.FEATURE_IMPLEMENTATION,
            PromptArchetype.API_DESIGN,
            PromptArchetype.FRONTEND_IMPLEMENTATION,
            PromptArchetype.BACKEND_IMPLEMENTATION,
            PromptArchetype.FULLSTACK_IMPLEMENTATION -> GENERATION

            // Bug & debugging → VALIDATION
            PromptArchetype.BUG_ANALYSIS,
            PromptArchetype.VERIFICATION -> VALIDATION

            // Documentation → DOCUMENTATION
            PromptArchetype.DOCS_ALIGNMENT -> DOCUMENTATION

            // DevOps / CI/CD → RUNTIME
            PromptArchetype.CI_CD_ANALYSIS -> RUNTIME

            // Extraction & tooling → GENERATION
            PromptArchetype.FRAMEWORK_EXTRACTION,
            PromptArchetype.TOOLING_CREATION -> GENERATION

            // ML → GENERATION
            PromptArchetype.ML_PIPELINE,
            PromptArchetype.MODEL_TRAINING -> GENERATION

            // IntelliJ → GENERATION
            PromptArchetype.PLUGIN_DEVELOPMENT,
            PromptArchetype.EXTENSION_POINT -> GENERATION

            // Workspace & runtime
            PromptArchetype.TEMPORARY_WORKSPACE,
            PromptArchetype.WORKSPACE_ISOLATION -> DOMAIN

            PromptArchetype.RAM_OPTIMIZATION -> MEMORY

            PromptArchetype.TRACEABILITY_TIMELINE -> EVIDENCE

            // Ollama & model
            PromptArchetype.OLLAMA_INTEGRATION,
            PromptArchetype.MODEL_ROUTING -> RUNTIME

            // Security & privacy → REVIEW
            PromptArchetype.SECRET_HANDLING,
            PromptArchetype.PLUGIN_PERMISSIONS,
            PromptArchetype.SUPPLY_CHAIN_DEPENDENCY,
            PromptArchetype.LOGGING_TRACE_SAFETY,
            PromptArchetype.DATA_PRIVACY,
            PromptArchetype.WHITE_HAT_SECURITY -> REVIEW

            // Composite / meta
            PromptArchetype.PERFORMANCE_ANALYSIS -> MEMORY
            PromptArchetype.DEPENDENCY_ANALYSIS -> EVIDENCE
            PromptArchetype.MIGRATION_PLANNING -> INTENT
            PromptArchetype.ONBOARDING -> EVIDENCE
            PromptArchetype.COMPARISON -> EVIDENCE
            PromptArchetype.COMPLIANCE_CHECK -> REVIEW
        }

        /**
         * Parse a unit type from a string (case-insensitive), returning [INTENT] as default.
         */
        fun fromString(value: String): PromptUnitType =
            entries.find { it.name.equals(value, ignoreCase = true) } ?: INTENT
    }
}
