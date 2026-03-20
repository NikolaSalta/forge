package forge.core.prompt

import forge.core.TaskType

/**
 * Recognized prompt archetypes. Each archetype maps a user intent pattern
 * to one or more [TaskType]s and carries heuristic keywords for fast,
 * LLM-free detection.
 */
enum class PromptArchetype(
    val label: String,
    val keywords: List<String>,
    val primaryTaskType: TaskType,
    val canParallel: Boolean = true,
    val defaultOutputShape: OutputShape = OutputShape.ANALYSIS
) {
    // ── Analysis archetypes ──────────────────────────────────────────────────
    ARCHITECTURE_ANALYSIS(
        "Architecture Analysis",
        listOf("architecture", "structure", "modules", "layers", "design patterns", "component"),
        TaskType.ARCHITECTURE_REVIEW
    ),
    REPOSITORY_OVERVIEW(
        "Repository Overview",
        listOf("overview", "summary", "what is this", "explain project", "tech stack"),
        TaskType.PROJECT_OVERVIEW
    ),
    BUILD_SYSTEM_ANALYSIS(
        "Build System Analysis",
        listOf("build", "gradle", "maven", "cmake", "makefile", "dependencies", "how to run"),
        TaskType.BUILD_AND_RUN_ANALYSIS
    ),
    FULL_REPO_ANALYSIS(
        "Full Repository Analysis",
        listOf("full analysis", "comprehensive analysis", "analyze everything", "deep dive"),
        TaskType.REPO_ANALYSIS
    ),

    // ── Review archetypes ────────────────────────────────────────────────────
    CODE_REVIEW(
        "Code Review",
        listOf("review", "code quality", "maintainability", "tech debt", "anti-pattern", "smell"),
        TaskType.CODE_QUALITY_REVIEW,
        defaultOutputShape = OutputShape.REVIEW_TABLE
    ),
    SECURITY_REVIEW(
        "Security Review",
        listOf("security", "vulnerability", "auth", "owasp", "injection", "xss", "csrf", "encryption"),
        TaskType.SECURITY_REVIEW,
        defaultOutputShape = OutputShape.REVIEW_TABLE
    ),
    QA_REVIEW(
        "QA Review",
        listOf("qa", "test quality", "test strategy", "testing approach"),
        TaskType.QA_REVIEW,
        defaultOutputShape = OutputShape.REVIEW_TABLE
    ),
    TEST_COVERAGE(
        "Test Coverage Analysis",
        listOf("test coverage", "coverage", "untested", "missing tests"),
        TaskType.TEST_COVERAGE_REVIEW,
        defaultOutputShape = OutputShape.REVIEW_TABLE
    ),

    // ── Implementation archetypes ────────────────────────────────────────────
    FEATURE_IMPLEMENTATION(
        "Feature Implementation",
        listOf("implement", "add feature", "create feature", "build feature", "new feature"),
        TaskType.IMPLEMENT_FEATURE,
        canParallel = false,
        defaultOutputShape = OutputShape.CODE_BLOCK
    ),
    API_DESIGN(
        "API Design",
        listOf("api", "endpoint", "rest", "graphql", "schema", "contract"),
        TaskType.API_DESIGN,
        canParallel = false,
        defaultOutputShape = OutputShape.CODE_BLOCK
    ),
    FRONTEND_IMPLEMENTATION(
        "Frontend Implementation",
        listOf("frontend", "ui", "component", "react", "vue", "angular", "css", "layout"),
        TaskType.FRONTEND_DESIGN,
        canParallel = false,
        defaultOutputShape = OutputShape.CODE_BLOCK
    ),
    BACKEND_IMPLEMENTATION(
        "Backend Implementation",
        listOf("backend", "service", "server", "database", "orm", "migration"),
        TaskType.BACKEND_DESIGN,
        canParallel = false,
        defaultOutputShape = OutputShape.CODE_BLOCK
    ),
    FULLSTACK_IMPLEMENTATION(
        "Fullstack Implementation",
        listOf("fullstack", "full stack", "end to end", "frontend and backend"),
        TaskType.FULLSTACK_FEATURE,
        canParallel = false,
        defaultOutputShape = OutputShape.CODE_BLOCK
    ),

    // ── Bug & debugging archetypes ───────────────────────────────────────────
    BUG_ANALYSIS(
        "Bug Analysis",
        listOf("bug", "error", "exception", "crash", "fix", "debug", "broken", "failing"),
        TaskType.BUG_ANALYSIS,
        canParallel = false
    ),
    VERIFICATION(
        "Feature Verification",
        listOf("verify", "check", "validate", "confirm", "works correctly"),
        TaskType.VERIFY_FEATURE
    ),

    // ── Documentation archetypes ─────────────────────────────────────────────
    DOCS_ALIGNMENT(
        "Documentation Alignment",
        listOf("documentation", "docs", "readme", "javadoc", "comment", "doc alignment"),
        TaskType.DOCS_ALIGNMENT,
        defaultOutputShape = OutputShape.NARRATIVE
    ),

    // ── DevOps archetypes ────────────────────────────────────────────────────
    CI_CD_ANALYSIS(
        "CI/CD Analysis",
        listOf("ci", "cd", "pipeline", "github actions", "jenkins", "deploy", "dockerfile"),
        TaskType.CI_CD_ANALYSIS,
        defaultOutputShape = OutputShape.CHECKLIST
    ),

    // ── Extraction & tooling archetypes ──────────────────────────────────────
    FRAMEWORK_EXTRACTION(
        "Framework Extraction",
        listOf("extract", "framework", "library", "reusable", "refactor into library"),
        TaskType.FRAMEWORK_EXTRACTION,
        canParallel = false
    ),
    TOOLING_CREATION(
        "Tooling Creation",
        listOf("tool", "script", "utility", "automation", "cli tool"),
        TaskType.TOOLING_CREATION,
        canParallel = false
    ),

    // ── ML archetypes ────────────────────────────────────────────────────────
    ML_PIPELINE(
        "ML Pipeline Design",
        listOf("ml", "machine learning", "data pipeline", "training pipeline", "feature engineering"),
        TaskType.ML_PIPELINE_DESIGN,
        canParallel = false
    ),
    MODEL_TRAINING(
        "Model Training Plan",
        listOf("model training", "hyperparameter", "fine-tune", "training plan"),
        TaskType.MODEL_TRAINING_PLAN
    ),

    // ── IntelliJ-specific archetypes ─────────────────────────────────────────
    PLUGIN_DEVELOPMENT(
        "Plugin Development",
        listOf("plugin", "intellij plugin", "ide plugin"),
        TaskType.PLUGIN_DEVELOPMENT,
        canParallel = false
    ),
    EXTENSION_POINT(
        "Extension Point Implementation",
        listOf("extension point", "ep implementation", "plugin extension"),
        TaskType.EXTENSION_POINT_IMPL,
        canParallel = false
    ),

    // ── Workspace & runtime archetypes ──────────────────────────────────────
    TEMPORARY_WORKSPACE(
        "Temporary Workspace",
        listOf("temp workspace", "scratch", "workspace layout", "sandbox", "temp storage"),
        TaskType.TOOLING_CREATION
    ),
    RAM_OPTIMIZATION(
        "RAM Optimization",
        listOf("memory", "ram", "heap", "gc", "garbage collection", "memory leak", "oom", "out of memory"),
        TaskType.CODE_QUALITY_REVIEW
    ),
    TRACEABILITY_TIMELINE(
        "Traceability Timeline",
        listOf("trace", "timeline", "audit trail", "execution history", "changelog", "activity log"),
        TaskType.REPO_ANALYSIS
    ),

    // ── Ollama & model archetypes ─────────────────────────────────────────
    OLLAMA_INTEGRATION(
        "Ollama Integration",
        listOf("ollama", "model pull", "model list", "model management", "local model", "ollama api"),
        TaskType.BUILD_AND_RUN_ANALYSIS
    ),
    MODEL_ROUTING(
        "Model Routing",
        listOf("model selection", "model routing", "which model", "model config", "model strategy"),
        TaskType.BUILD_AND_RUN_ANALYSIS
    ),

    // ── Security & privacy archetypes ─────────────────────────────────────
    WORKSPACE_ISOLATION(
        "Workspace Isolation",
        listOf("isolation", "workspace isolation", "sandboxing", "task isolation", "project boundary"),
        TaskType.ARCHITECTURE_REVIEW
    ),
    SECRET_HANDLING(
        "Secret Handling",
        listOf("secret", "credential", "api key", "token handling", "password", "vault", "sensitive config"),
        TaskType.SECURITY_REVIEW
    ),
    PLUGIN_PERMISSIONS(
        "Plugin Permissions",
        listOf("permission", "plugin permission", "access control", "capability surface", "access boundary"),
        TaskType.SECURITY_REVIEW
    ),
    SUPPLY_CHAIN_DEPENDENCY(
        "Supply Chain Dependency",
        listOf("supply chain", "dependency audit", "transitive dependency", "package trust", "artifact safety"),
        TaskType.SECURITY_REVIEW
    ),
    LOGGING_TRACE_SAFETY(
        "Logging & Trace Safety",
        listOf("logging safety", "pii in logs", "log sanitization", "trace redaction", "log exposure"),
        TaskType.SECURITY_REVIEW
    ),
    DATA_PRIVACY(
        "Data Privacy",
        listOf("privacy", "gdpr", "pii", "data retention", "data masking", "data protection"),
        TaskType.SECURITY_REVIEW
    ),
    WHITE_HAT_SECURITY(
        "White Hat Security",
        listOf("penetration test", "white hat", "ethical hacking", "security audit", "vulnerability scan"),
        TaskType.SECURITY_REVIEW
    ),

    // ── Composite / meta archetypes ──────────────────────────────────────────
    PERFORMANCE_ANALYSIS(
        "Performance Analysis",
        listOf("performance", "slow", "optimize", "bottleneck", "profiling", "memory leak"),
        TaskType.CODE_QUALITY_REVIEW
    ),
    DEPENDENCY_ANALYSIS(
        "Dependency Analysis",
        listOf("dependency", "dependency graph", "outdated", "vulnerable dependency"),
        TaskType.BUILD_AND_RUN_ANALYSIS
    ),
    MIGRATION_PLANNING(
        "Migration Planning",
        listOf("migrate", "migration", "upgrade", "port", "convert"),
        TaskType.ARCHITECTURE_REVIEW,
        canParallel = false
    ),
    ONBOARDING(
        "Developer Onboarding",
        listOf("onboarding", "getting started", "how does this work", "explain codebase"),
        TaskType.PROJECT_OVERVIEW
    ),
    COMPARISON(
        "Architecture Comparison",
        listOf("compare", "difference", "versus", "alternative", "tradeoff"),
        TaskType.ARCHITECTURE_REVIEW
    ),
    COMPLIANCE_CHECK(
        "Compliance Check",
        listOf("compliance", "license", "legal", "regulation", "gdpr", "hipaa"),
        TaskType.SECURITY_REVIEW
    );

    companion object {
        /**
         * Score each archetype against the given prompt text using keyword matching.
         * Returns archetypes with score > 0, sorted descending by score.
         */
        fun score(prompt: String): List<ScoredArchetype> {
            val lower = prompt.lowercase()
            return entries.mapNotNull { archetype ->
                val hits = archetype.keywords.count { keyword -> lower.contains(keyword) }
                if (hits > 0) ScoredArchetype(archetype, hits) else null
            }.sortedByDescending { it.score }
        }
    }
}

/**
 * An archetype with its heuristic match score (number of keyword hits).
 */
data class ScoredArchetype(
    val archetype: PromptArchetype,
    val score: Int
)
