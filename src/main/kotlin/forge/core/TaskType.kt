package forge.core

/**
 * All supported task types in the Forge platform.
 * Each task type has metadata about what kind of work it requires.
 */
enum class TaskType(
    val displayName: String,
    val description: String,
    val generatesCode: Boolean = false,
    val requiresDeepAnalysis: Boolean = false,
    val modelRole: ModelRole = ModelRole.REASON
) {
    REPO_ANALYSIS(
        "Repository Analysis",
        "Full analysis of repository structure, architecture, and key components",
        requiresDeepAnalysis = true
    ),
    PROJECT_OVERVIEW(
        "Project Overview",
        "High-level overview of the project: purpose, tech stack, structure"
    ),
    BUILD_AND_RUN_ANALYSIS(
        "Build & Run Analysis",
        "Analysis of build system, dependencies, and how to run the project"
    ),
    ARCHITECTURE_REVIEW(
        "Architecture Review",
        "Review of the software architecture, patterns, and design decisions",
        requiresDeepAnalysis = true
    ),
    SECURITY_REVIEW(
        "Security Review",
        "Security audit: vulnerabilities, dependency issues, auth patterns",
        requiresDeepAnalysis = true
    ),
    CODE_QUALITY_REVIEW(
        "Code Quality Review",
        "Code quality assessment: patterns, anti-patterns, tech debt",
        requiresDeepAnalysis = true
    ),
    BUG_ANALYSIS(
        "Bug Analysis",
        "Analysis and diagnosis of a specific bug or issue",
        requiresDeepAnalysis = true
    ),
    IMPLEMENT_FEATURE(
        "Implement Feature",
        "Generate code to implement a new feature",
        generatesCode = true,
        requiresDeepAnalysis = true,
        modelRole = ModelRole.CODE
    ),
    VERIFY_FEATURE(
        "Verify Feature",
        "Verify that a feature works correctly, review implementation"
    ),
    API_DESIGN(
        "API Design",
        "Design or review API endpoints, contracts, and schemas",
        generatesCode = true,
        modelRole = ModelRole.CODE
    ),
    FRONTEND_DESIGN(
        "Frontend Design",
        "Design or implement frontend components and UI",
        generatesCode = true,
        modelRole = ModelRole.CODE
    ),
    BACKEND_DESIGN(
        "Backend Design",
        "Design or implement backend services and logic",
        generatesCode = true,
        modelRole = ModelRole.CODE
    ),
    FULLSTACK_FEATURE(
        "Fullstack Feature",
        "Implement a feature spanning frontend and backend",
        generatesCode = true,
        requiresDeepAnalysis = true,
        modelRole = ModelRole.CODE
    ),
    QA_REVIEW(
        "QA Review",
        "Review test quality, test strategy, and QA processes"
    ),
    TEST_COVERAGE_REVIEW(
        "Test Coverage Review",
        "Analyze test coverage and suggest improvements",
        requiresDeepAnalysis = true
    ),
    DOCS_ALIGNMENT(
        "Documentation Alignment",
        "Check if docs match the actual code and suggest fixes",
        generatesCode = true
    ),
    CI_CD_ANALYSIS(
        "CI/CD Analysis",
        "Analyze CI/CD pipelines, configs, and deployment processes"
    ),
    FRAMEWORK_EXTRACTION(
        "Framework Extraction",
        "Extract reusable framework/library from existing code",
        generatesCode = true,
        requiresDeepAnalysis = true,
        modelRole = ModelRole.CODE
    ),
    TOOLING_CREATION(
        "Tooling Creation",
        "Create development tools, scripts, or utilities",
        generatesCode = true,
        modelRole = ModelRole.CODE
    ),
    ML_PIPELINE_DESIGN(
        "ML Pipeline Design",
        "Design or review ML/data pipelines",
        generatesCode = true,
        requiresDeepAnalysis = true,
        modelRole = ModelRole.CODE
    ),
    MODEL_TRAINING_PLAN(
        "Model Training Plan",
        "Plan model training: data, architecture, hyperparameters",
        requiresDeepAnalysis = true
    ),
    PLUGIN_DEVELOPMENT(
        "Plugin Development",
        "Develop an IntelliJ plugin",
        generatesCode = true,
        requiresDeepAnalysis = true,
        modelRole = ModelRole.CODE
    ),
    EXTENSION_POINT_IMPL(
        "Extension Point Implementation",
        "Implement an IntelliJ extension point",
        generatesCode = true,
        requiresDeepAnalysis = true,
        modelRole = ModelRole.CODE
    );

    companion object {
        fun fromString(value: String): TaskType? {
            return entries.find {
                it.name.equals(value, ignoreCase = true) ||
                it.displayName.equals(value, ignoreCase = true)
            }
        }
    }
}

/**
 * Which model role to use for each task.
 */
enum class ModelRole {
    CLASSIFY,   // Fast, small model for classification
    REASON,     // Reasoning model for analysis
    CODE,       // Code-specialized model
    SUMMARIZE,  // Summarization model
    EMBED,      // Embedding model
    VISION      // Vision/OCR model
}
