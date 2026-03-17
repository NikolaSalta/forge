package forge.retrieval

import forge.core.TaskType
import forge.workspace.Database
import forge.workspace.EvidenceRecord
import forge.workspace.FileRecord
import java.nio.file.Files
import java.nio.file.Path

/**
 * Categories of evidence that can be collected about a repository.
 */
enum class EvidenceCategory {
    BUILD_SYSTEM,
    SOURCE_ROOTS,
    TEST_ROOTS,
    LANGUAGES,
    KEY_MODULES,
    KEY_FILES,
    DEPENDENCIES,
    MODULE_MAP,
    CONFIG_FILES,
    ARCHITECTURE,
    CONVENTIONS,
    INTEGRATION_POINTS,
    TEST_PATTERNS,
    AUTH_PATTERNS,
    API_ENDPOINTS,
    CI_CD_SIGNALS,
    RUNTIME_SHAPE,
    REPRESENTATIVE_FILES,
    EXTENSION_POINTS,
    MODULE_GRAPH,
    SERVICES,
    PSI_PATTERNS
}

/**
 * Result of an evidence collection run.
 */
data class EvidenceResult(
    val count: Int,
    val categories: Set<String>
)

/**
 * Result of evaluating the evidence gate for a specific task type.
 */
data class GateResult(
    val passed: Boolean,
    val missing: List<String>,
    val collected: Int,
    val required: Int
)

/**
 * Collects evidence about a repository by auto-detecting build systems,
 * languages, source roots, CI/CD signals, and other structural facts.
 * Evidence is persisted to the [Database] and can later be checked
 * against a per-[TaskType] gate to determine whether enough context
 * has been gathered to proceed.
 */
class EvidenceCollector {

    companion object {
        // ----- Build-file -> Build-system display name mapping -----
        private val BUILD_FILE_TO_SYSTEM: Map<String, String> = mapOf(
            "build.gradle" to "Gradle",
            "build.gradle.kts" to "Gradle (Kotlin DSL)",
            "pom.xml" to "Maven",
            "package.json" to "npm",
            "Cargo.toml" to "Cargo",
            "go.mod" to "Go Modules",
            "pyproject.toml" to "Python (pyproject)",
            "setup.py" to "Python (setuptools)",
            "CMakeLists.txt" to "CMake",
            "build.sbt" to "sbt",
            "Gemfile" to "Bundler",
            "composer.json" to "Composer"
        )

        // ----- CI/CD file markers -----
        private val CI_CD_MARKERS: Map<String, String> = mapOf(
            ".github/workflows" to "GitHub Actions",
            "Jenkinsfile" to "Jenkins",
            ".gitlab-ci.yml" to "GitLab CI",
            ".circleci/config.yml" to "CircleCI",
            ".travis.yml" to "Travis CI",
            "azure-pipelines.yml" to "Azure Pipelines",
            "bitbucket-pipelines.yml" to "Bitbucket Pipelines"
        )

        // ----- Required evidence per task type -----
        // Each task type has a set of evidence categories that must be
        // collected before the evidence gate allows proceeding.
        private val REQUIRED_EVIDENCE: Map<TaskType, Set<EvidenceCategory>> = mapOf(
            TaskType.REPO_ANALYSIS to setOf(
                EvidenceCategory.BUILD_SYSTEM,
                EvidenceCategory.LANGUAGES,
                EvidenceCategory.SOURCE_ROOTS,
                EvidenceCategory.KEY_MODULES,
                EvidenceCategory.DEPENDENCIES,
                EvidenceCategory.CI_CD_SIGNALS,
                EvidenceCategory.TEST_ROOTS,
                EvidenceCategory.EXTENSION_POINTS,
                EvidenceCategory.MODULE_GRAPH,
                EvidenceCategory.SERVICES,
                EvidenceCategory.PSI_PATTERNS
            ),
            TaskType.PROJECT_OVERVIEW to setOf(
                EvidenceCategory.BUILD_SYSTEM,
                EvidenceCategory.LANGUAGES,
                EvidenceCategory.SOURCE_ROOTS
            ),
            TaskType.BUILD_AND_RUN_ANALYSIS to setOf(
                EvidenceCategory.BUILD_SYSTEM,
                EvidenceCategory.RUNTIME_SHAPE,
                EvidenceCategory.CI_CD_SIGNALS
            ),
            TaskType.ARCHITECTURE_REVIEW to setOf(
                EvidenceCategory.BUILD_SYSTEM,
                EvidenceCategory.LANGUAGES,
                EvidenceCategory.SOURCE_ROOTS,
                EvidenceCategory.KEY_MODULES,
                EvidenceCategory.DEPENDENCIES,
                EvidenceCategory.MODULE_MAP,
                EvidenceCategory.ARCHITECTURE,
                EvidenceCategory.EXTENSION_POINTS,
                EvidenceCategory.MODULE_GRAPH,
                EvidenceCategory.SERVICES,
                EvidenceCategory.PSI_PATTERNS
            ),
            TaskType.SECURITY_REVIEW to setOf(
                EvidenceCategory.BUILD_SYSTEM,
                EvidenceCategory.LANGUAGES,
                EvidenceCategory.DEPENDENCIES,
                EvidenceCategory.AUTH_PATTERNS,
                EvidenceCategory.API_ENDPOINTS,
                EvidenceCategory.CONFIG_FILES
            ),
            TaskType.CODE_QUALITY_REVIEW to setOf(
                EvidenceCategory.LANGUAGES,
                EvidenceCategory.SOURCE_ROOTS,
                EvidenceCategory.CONVENTIONS,
                EvidenceCategory.TEST_PATTERNS,
                EvidenceCategory.KEY_FILES
            ),
            TaskType.BUG_ANALYSIS to setOf(
                EvidenceCategory.BUILD_SYSTEM,
                EvidenceCategory.LANGUAGES,
                EvidenceCategory.SOURCE_ROOTS,
                EvidenceCategory.DEPENDENCIES,
                EvidenceCategory.TEST_PATTERNS
            ),
            TaskType.IMPLEMENT_FEATURE to setOf(
                EvidenceCategory.BUILD_SYSTEM,
                EvidenceCategory.LANGUAGES,
                EvidenceCategory.SOURCE_ROOTS,
                EvidenceCategory.KEY_MODULES,
                EvidenceCategory.CONVENTIONS,
                EvidenceCategory.DEPENDENCIES,
                EvidenceCategory.REPRESENTATIVE_FILES,
                EvidenceCategory.EXTENSION_POINTS,
                EvidenceCategory.MODULE_GRAPH,
                EvidenceCategory.SERVICES,
                EvidenceCategory.PSI_PATTERNS
            ),
            TaskType.VERIFY_FEATURE to setOf(
                EvidenceCategory.BUILD_SYSTEM,
                EvidenceCategory.SOURCE_ROOTS,
                EvidenceCategory.TEST_ROOTS,
                EvidenceCategory.TEST_PATTERNS
            ),
            TaskType.API_DESIGN to setOf(
                EvidenceCategory.BUILD_SYSTEM,
                EvidenceCategory.LANGUAGES,
                EvidenceCategory.API_ENDPOINTS,
                EvidenceCategory.CONVENTIONS,
                EvidenceCategory.DEPENDENCIES
            ),
            TaskType.FRONTEND_DESIGN to setOf(
                EvidenceCategory.BUILD_SYSTEM,
                EvidenceCategory.LANGUAGES,
                EvidenceCategory.SOURCE_ROOTS,
                EvidenceCategory.KEY_MODULES,
                EvidenceCategory.CONVENTIONS
            ),
            TaskType.BACKEND_DESIGN to setOf(
                EvidenceCategory.BUILD_SYSTEM,
                EvidenceCategory.LANGUAGES,
                EvidenceCategory.SOURCE_ROOTS,
                EvidenceCategory.KEY_MODULES,
                EvidenceCategory.DEPENDENCIES,
                EvidenceCategory.CONVENTIONS
            ),
            TaskType.FULLSTACK_FEATURE to setOf(
                EvidenceCategory.BUILD_SYSTEM,
                EvidenceCategory.LANGUAGES,
                EvidenceCategory.SOURCE_ROOTS,
                EvidenceCategory.KEY_MODULES,
                EvidenceCategory.DEPENDENCIES,
                EvidenceCategory.CONVENTIONS,
                EvidenceCategory.API_ENDPOINTS,
                EvidenceCategory.REPRESENTATIVE_FILES
            ),
            TaskType.QA_REVIEW to setOf(
                EvidenceCategory.TEST_ROOTS,
                EvidenceCategory.TEST_PATTERNS,
                EvidenceCategory.LANGUAGES,
                EvidenceCategory.CI_CD_SIGNALS
            ),
            TaskType.TEST_COVERAGE_REVIEW to setOf(
                EvidenceCategory.TEST_ROOTS,
                EvidenceCategory.TEST_PATTERNS,
                EvidenceCategory.SOURCE_ROOTS,
                EvidenceCategory.LANGUAGES
            ),
            TaskType.DOCS_ALIGNMENT to setOf(
                EvidenceCategory.SOURCE_ROOTS,
                EvidenceCategory.LANGUAGES,
                EvidenceCategory.KEY_FILES
            ),
            TaskType.CI_CD_ANALYSIS to setOf(
                EvidenceCategory.BUILD_SYSTEM,
                EvidenceCategory.CI_CD_SIGNALS,
                EvidenceCategory.RUNTIME_SHAPE
            ),
            TaskType.FRAMEWORK_EXTRACTION to setOf(
                EvidenceCategory.BUILD_SYSTEM,
                EvidenceCategory.LANGUAGES,
                EvidenceCategory.SOURCE_ROOTS,
                EvidenceCategory.KEY_MODULES,
                EvidenceCategory.DEPENDENCIES,
                EvidenceCategory.ARCHITECTURE,
                EvidenceCategory.REPRESENTATIVE_FILES
            ),
            TaskType.TOOLING_CREATION to setOf(
                EvidenceCategory.BUILD_SYSTEM,
                EvidenceCategory.LANGUAGES,
                EvidenceCategory.SOURCE_ROOTS,
                EvidenceCategory.CONVENTIONS
            ),
            TaskType.ML_PIPELINE_DESIGN to setOf(
                EvidenceCategory.BUILD_SYSTEM,
                EvidenceCategory.LANGUAGES,
                EvidenceCategory.DEPENDENCIES,
                EvidenceCategory.SOURCE_ROOTS,
                EvidenceCategory.KEY_MODULES,
                EvidenceCategory.CONFIG_FILES
            ),
            TaskType.MODEL_TRAINING_PLAN to setOf(
                EvidenceCategory.BUILD_SYSTEM,
                EvidenceCategory.LANGUAGES,
                EvidenceCategory.DEPENDENCIES,
                EvidenceCategory.CONFIG_FILES
            ),
            TaskType.PLUGIN_DEVELOPMENT to setOf(
                EvidenceCategory.BUILD_SYSTEM,
                EvidenceCategory.LANGUAGES,
                EvidenceCategory.SOURCE_ROOTS,
                EvidenceCategory.KEY_MODULES,
                EvidenceCategory.DEPENDENCIES,
                EvidenceCategory.EXTENSION_POINTS,
                EvidenceCategory.MODULE_GRAPH,
                EvidenceCategory.SERVICES,
                EvidenceCategory.PSI_PATTERNS
            ),
            TaskType.EXTENSION_POINT_IMPL to setOf(
                EvidenceCategory.BUILD_SYSTEM,
                EvidenceCategory.LANGUAGES,
                EvidenceCategory.SOURCE_ROOTS,
                EvidenceCategory.DEPENDENCIES,
                EvidenceCategory.EXTENSION_POINTS,
                EvidenceCategory.SERVICES
            )
        )

        // ----- Extension -> language display name (for counting) -----
        private val EXTENSION_TO_LANGUAGE_NAME: Map<String, String> = mapOf(
            "kt" to "Kotlin", "kts" to "Kotlin",
            "java" to "Java",
            "ts" to "TypeScript", "tsx" to "TypeScript",
            "js" to "JavaScript", "jsx" to "JavaScript",
            "py" to "Python",
            "go" to "Go",
            "rs" to "Rust",
            "cs" to "C#",
            "cpp" to "C++", "cc" to "C++", "cxx" to "C++",
            "c" to "C",
            "h" to "C/C++ Header", "hpp" to "C++ Header",
            "scala" to "Scala",
            "rb" to "Ruby",
            "php" to "PHP",
            "swift" to "Swift"
        )
    }

    // -----------------------------------------------------------------------
    //  Main collection entry point
    // -----------------------------------------------------------------------

    /**
     * Runs all auto-detection heuristics against the files stored in [db]
     * and the on-disk repository at [repoPath]. Each detected fact is
     * persisted as an evidence record.
     *
     * @return An [EvidenceResult] summarizing how many evidence records
     *         were created and which categories were covered.
     */
    fun collect(
        taskId: String,
        taskType: TaskType,
        db: Database,
        repoPath: Path
    ): EvidenceResult {
        val resolvedRoot = repoPath.toAbsolutePath().normalize()
        val collectedCategories = mutableSetOf<String>()
        var totalCount = 0

        // Determine which evidence categories are required for this task.
        val required = REQUIRED_EVIDENCE[taskType] ?: emptySet()

        // Run detectors for each required category (and always-useful ones).
        val categoriesToCollect = required + setOf(
            EvidenceCategory.BUILD_SYSTEM,
            EvidenceCategory.LANGUAGES,
            EvidenceCategory.SOURCE_ROOTS
        )

        for (category in categoriesToCollect) {
            val count = when (category) {
                EvidenceCategory.BUILD_SYSTEM -> detectBuildSystem(taskId, db)
                EvidenceCategory.LANGUAGES -> detectLanguages(taskId, db)
                EvidenceCategory.SOURCE_ROOTS -> detectSourceRoots(taskId, db)
                EvidenceCategory.TEST_ROOTS -> detectTestRoots(taskId, db)
                EvidenceCategory.KEY_MODULES -> detectKeyModules(taskId, db, resolvedRoot)
                EvidenceCategory.KEY_FILES -> detectKeyFiles(taskId, db)
                EvidenceCategory.DEPENDENCIES -> detectDependencies(taskId, db, resolvedRoot)
                EvidenceCategory.MODULE_MAP -> detectModuleMap(taskId, db)
                EvidenceCategory.CONFIG_FILES -> detectConfigFiles(taskId, db)
                EvidenceCategory.ARCHITECTURE -> detectArchitecture(taskId, db)
                EvidenceCategory.CONVENTIONS -> detectConventions(taskId, db, resolvedRoot)
                EvidenceCategory.INTEGRATION_POINTS -> detectIntegrationPoints(taskId, db, resolvedRoot)
                EvidenceCategory.TEST_PATTERNS -> detectTestPatterns(taskId, db, resolvedRoot)
                EvidenceCategory.AUTH_PATTERNS -> detectAuthPatterns(taskId, db, resolvedRoot)
                EvidenceCategory.API_ENDPOINTS -> detectApiEndpoints(taskId, db, resolvedRoot)
                EvidenceCategory.CI_CD_SIGNALS -> detectCiCdSignals(taskId, db)
                EvidenceCategory.RUNTIME_SHAPE -> detectRuntimeShape(taskId, db)
                EvidenceCategory.REPRESENTATIVE_FILES -> detectRepresentativeFiles(taskId, db)
                EvidenceCategory.EXTENSION_POINTS -> detectExtensionPoints(taskId, db)
                EvidenceCategory.MODULE_GRAPH -> detectModuleGraph(taskId, db)
                EvidenceCategory.SERVICES -> detectServices(taskId, db)
                EvidenceCategory.PSI_PATTERNS -> detectPsiPatterns(taskId, db)
            }
            if (count > 0) {
                collectedCategories.add(category.name)
            }
            totalCount += count
        }

        return EvidenceResult(count = totalCount, categories = collectedCategories)
    }

    // -----------------------------------------------------------------------
    //  Evidence gate
    // -----------------------------------------------------------------------

    /**
     * Evaluates whether enough evidence has been collected for [taskType].
     * Compares the categories present in the DB for [taskId] against the
     * required set.
     */
    fun checkGate(
        taskId: String,
        taskType: TaskType,
        db: Database
    ): GateResult {
        val required = REQUIRED_EVIDENCE[taskType] ?: emptySet()
        if (required.isEmpty()) {
            return GateResult(passed = true, missing = emptyList(), collected = 0, required = 0)
        }

        val existingRecords = db.getEvidenceByTask(taskId)
        val collectedCategoryNames = existingRecords.map { it.category }.toSet()

        val missingCategories = required
            .map { it.name }
            .filter { it !in collectedCategoryNames }

        return GateResult(
            passed = missingCategories.isEmpty(),
            missing = missingCategories,
            collected = collectedCategoryNames.size,
            required = required.size
        )
    }

    // -----------------------------------------------------------------------
    //  Individual detectors
    // -----------------------------------------------------------------------

    /**
     * Detects the build system(s) from build files stored in DB.
     */
    private fun detectBuildSystem(taskId: String, db: Database): Int {
        val buildFiles = db.getFilesByCategory("build")
        if (buildFiles.isEmpty()) return 0

        val detectedSystems = mutableSetOf<String>()
        var count = 0

        for (file in buildFiles) {
            val fileName = file.relativePath.substringAfterLast('/')
            // Check exact names and prefixes.
            val system = BUILD_FILE_TO_SYSTEM[fileName]
                ?: BUILD_FILE_TO_SYSTEM.entries.firstOrNull { fileName.startsWith(it.key) }?.value

            if (system != null && system !in detectedSystems) {
                detectedSystems.add(system)
                db.insertEvidence(taskId, EvidenceCategory.BUILD_SYSTEM.name, "build_system", system)
                count++
            }

            // Also record the build file itself.
            db.insertEvidence(taskId, EvidenceCategory.BUILD_SYSTEM.name, "build_file", file.relativePath)
            count++
        }

        return count
    }

    /**
     * Counts files by language extension and records the top 5 languages.
     */
    private fun detectLanguages(taskId: String, db: Database): Int {
        val sourceFiles = db.getFilesByCategory("source") + db.getFilesByCategory("test")
        if (sourceFiles.isEmpty()) return 0

        // Count by display-name language.
        val langCounts = mutableMapOf<String, Int>()
        for (file in sourceFiles) {
            val ext = file.relativePath.substringAfterLast('.', "").lowercase()
            val langName = EXTENSION_TO_LANGUAGE_NAME[ext] ?: continue
            langCounts[langName] = (langCounts[langName] ?: 0) + 1
        }

        val topLanguages = langCounts.entries
            .sortedByDescending { it.value }
            .take(5)

        var count = 0
        for ((index, entry) in topLanguages.withIndex()) {
            db.insertEvidence(
                taskId,
                EvidenceCategory.LANGUAGES.name,
                "language_rank_${index + 1}",
                "${entry.key} (${entry.value} files)"
            )
            count++
        }
        return count
    }

    /**
     * Identifies the directories that contain the most source files.
     */
    private fun detectSourceRoots(taskId: String, db: Database): Int {
        val sourceFiles = db.getFilesByCategory("source")
        if (sourceFiles.isEmpty()) return 0

        // Count files per top-level directory segment.
        val dirCounts = mutableMapOf<String, Int>()
        for (file in sourceFiles) {
            val segments = file.relativePath.replace('\\', '/').split('/')
            if (segments.size >= 2) {
                // Use the first two directory segments as the "root".
                val root = segments.take(2).joinToString("/")
                dirCounts[root] = (dirCounts[root] ?: 0) + 1
            } else {
                dirCounts["."] = (dirCounts["."] ?: 0) + 1
            }
        }

        val topRoots = dirCounts.entries
            .sortedByDescending { it.value }
            .take(5)

        var count = 0
        for (entry in topRoots) {
            db.insertEvidence(
                taskId,
                EvidenceCategory.SOURCE_ROOTS.name,
                "source_root",
                "${entry.key} (${entry.value} files)"
            )
            count++
        }
        return count
    }

    /**
     * Identifies directories that contain test files.
     */
    private fun detectTestRoots(taskId: String, db: Database): Int {
        val testFiles = db.getFilesByCategory("test")
        if (testFiles.isEmpty()) return 0

        val dirCounts = mutableMapOf<String, Int>()
        for (file in testFiles) {
            val segments = file.relativePath.replace('\\', '/').split('/')
            if (segments.size >= 2) {
                val root = segments.take(2).joinToString("/")
                dirCounts[root] = (dirCounts[root] ?: 0) + 1
            } else {
                dirCounts["."] = (dirCounts["."] ?: 0) + 1
            }
        }

        val topRoots = dirCounts.entries
            .sortedByDescending { it.value }
            .take(5)

        var count = 0
        for (entry in topRoots) {
            db.insertEvidence(
                taskId,
                EvidenceCategory.TEST_ROOTS.name,
                "test_root",
                "${entry.key} (${entry.value} files)"
            )
            count++
        }
        return count
    }

    /**
     * Delegates to [DependencyMapper] to find the most-imported modules.
     */
    private fun detectKeyModules(taskId: String, db: Database, repoPath: Path): Int {
        val mapper = DependencyMapper()
        val depMap = mapper.mapDependencies(db, repoPath, taskId)
        // DependencyMapper already persists KEY_MODULES evidence.
        return depMap.keyModules.size
    }

    /**
     * Identifies key files: largest source files and those with the most
     * chunks (complex files).
     */
    private fun detectKeyFiles(taskId: String, db: Database): Int {
        val sourceFiles = db.getFilesByCategory("source")
        if (sourceFiles.isEmpty()) return 0

        // Largest files by byte size.
        val largestFiles = sourceFiles
            .sortedByDescending { it.sizeBytes ?: 0L }
            .take(10)

        var count = 0
        for (file in largestFiles) {
            db.insertEvidence(
                taskId,
                EvidenceCategory.KEY_FILES.name,
                "large_file",
                "${file.relativePath} (${(file.sizeBytes ?: 0L) / 1024}KB)"
            )
            count++
        }
        return count
    }

    /**
     * Collects overall dependency statistics via [DependencyMapper].
     */
    private fun detectDependencies(taskId: String, db: Database, repoPath: Path): Int {
        val mapper = DependencyMapper()
        val depMap = mapper.mapDependencies(db, repoPath, taskId)
        // DependencyMapper already persists DEPENDENCIES evidence.
        return if (depMap.imports.isNotEmpty()) 2 else 0 // stats records
    }

    /**
     * Builds a module map from the directory structure.
     */
    private fun detectModuleMap(taskId: String, db: Database): Int {
        val allFiles = db.getAllFiles()
        if (allFiles.isEmpty()) return 0

        // Group files by their top-level directory.
        val moduleDirs = mutableMapOf<String, MutableSet<String>>()
        for (file in allFiles) {
            if (file.category != "source") continue
            val segments = file.relativePath.replace('\\', '/').split('/')
            if (segments.size >= 2) {
                val topDir = segments[0]
                val lang = file.language ?: "unknown"
                moduleDirs.getOrPut(topDir) { mutableSetOf() }.add(lang)
            }
        }

        var count = 0
        for ((dir, languages) in moduleDirs.entries.sortedByDescending { it.value.size }) {
            db.insertEvidence(
                taskId,
                EvidenceCategory.MODULE_MAP.name,
                "module",
                "$dir [${languages.joinToString(", ")}]"
            )
            count++
            if (count >= 20) break
        }
        return count
    }

    /**
     * Lists configuration files found in the repository.
     */
    private fun detectConfigFiles(taskId: String, db: Database): Int {
        val configFiles = db.getFilesByCategory("config")
        if (configFiles.isEmpty()) return 0

        var count = 0
        for (file in configFiles.take(20)) {
            db.insertEvidence(
                taskId,
                EvidenceCategory.CONFIG_FILES.name,
                "config_file",
                file.relativePath
            )
            count++
        }
        return count
    }

    /**
     * Infers architectural patterns from the directory and file structure.
     */
    private fun detectArchitecture(taskId: String, db: Database): Int {
        val sourceFiles = db.getFilesByCategory("source")
        if (sourceFiles.isEmpty()) return 0

        val normalizedPaths = sourceFiles.map { it.relativePath.replace('\\', '/').lowercase() }
        var count = 0

        // Detect common architectural patterns by directory naming.
        val patterns = mapOf(
            "MVC" to listOf("controller", "model", "view"),
            "MVVM" to listOf("viewmodel", "model", "view"),
            "Clean Architecture" to listOf("domain", "data", "presentation"),
            "Hexagonal" to listOf("adapter", "port", "domain"),
            "Layered" to listOf("service", "repository", "controller"),
            "Microservices" to listOf("service", "gateway", "registry")
        )

        for ((patternName, markers) in patterns) {
            val matchCount = markers.count { marker ->
                normalizedPaths.any { path ->
                    path.split('/').any { segment -> segment.contains(marker) }
                }
            }
            // If at least 2 out of 3 markers are present, record it.
            if (matchCount >= 2) {
                db.insertEvidence(
                    taskId,
                    EvidenceCategory.ARCHITECTURE.name,
                    "pattern",
                    "$patternName ($matchCount/${markers.size} markers found)"
                )
                count++
            }
        }

        // Detect layering by directory depth distribution.
        val depthCounts = mutableMapOf<Int, Int>()
        for (path in normalizedPaths) {
            val depth = path.split('/').size - 1
            depthCounts[depth] = (depthCounts[depth] ?: 0) + 1
        }
        val avgDepth = if (depthCounts.isNotEmpty()) {
            depthCounts.entries.sumOf { it.key * it.value }.toDouble() / sourceFiles.size
        } else 0.0

        db.insertEvidence(
            taskId,
            EvidenceCategory.ARCHITECTURE.name,
            "avg_nesting_depth",
            "%.1f".format(avgDepth)
        )
        count++

        return count
    }

    /**
     * Detects naming and structural conventions from file names.
     */
    private fun detectConventions(taskId: String, db: Database, repoPath: Path): Int {
        val sourceFiles = db.getFilesByCategory("source")
        if (sourceFiles.isEmpty()) return 0

        var count = 0

        // Detect naming convention: camelCase vs snake_case vs PascalCase.
        var camelCount = 0
        var snakeCount = 0
        var pascalCount = 0
        val camelPattern = Regex("""^[a-z][a-zA-Z0-9]*$""")
        val snakePattern = Regex("""^[a-z][a-z0-9_]*$""")
        val pascalPattern = Regex("""^[A-Z][a-zA-Z0-9]*$""")

        for (file in sourceFiles) {
            val baseName = file.relativePath.substringAfterLast('/').substringBeforeLast('.')
            when {
                pascalPattern.matches(baseName) -> pascalCount++
                snakePattern.matches(baseName) && baseName.contains('_') -> snakeCount++
                camelPattern.matches(baseName) -> camelCount++
            }
        }

        val dominantNaming = when {
            pascalCount >= camelCount && pascalCount >= snakeCount -> "PascalCase ($pascalCount files)"
            snakeCount >= camelCount -> "snake_case ($snakeCount files)"
            else -> "camelCase ($camelCount files)"
        }
        db.insertEvidence(taskId, EvidenceCategory.CONVENTIONS.name, "file_naming", dominantNaming)
        count++

        // Detect whether there's a consistent source layout.
        val hasMainSrc = sourceFiles.any {
            val p = it.relativePath.replace('\\', '/')
            p.startsWith("src/main/") || p.startsWith("src/")
        }
        val hasLib = sourceFiles.any {
            it.relativePath.replace('\\', '/').startsWith("lib/")
        }
        val hasApp = sourceFiles.any {
            it.relativePath.replace('\\', '/').startsWith("app/")
        }

        val layout = when {
            hasMainSrc -> "Maven/Gradle standard (src/main/...)"
            hasLib && hasApp -> "Rails-style (app/, lib/)"
            hasLib -> "Library layout (lib/)"
            hasApp -> "Application layout (app/)"
            else -> "Flat or custom"
        }
        db.insertEvidence(taskId, EvidenceCategory.CONVENTIONS.name, "source_layout", layout)
        count++

        return count
    }

    /**
     * Detects integration points -- files with many imports.
     * Delegates to [DependencyMapper] which already records these.
     */
    private fun detectIntegrationPoints(taskId: String, db: Database, repoPath: Path): Int {
        // DependencyMapper.mapDependencies already persists INTEGRATION_POINTS.
        val mapper = DependencyMapper()
        val depMap = mapper.mapDependencies(db, repoPath, taskId)
        return depMap.imports.count { it.value.size > 5 }.coerceAtMost(20)
    }

    /**
     * Detects test patterns: frameworks, test file conventions, test-to-source ratio.
     */
    private fun detectTestPatterns(taskId: String, db: Database, repoPath: Path): Int {
        val testFiles = db.getFilesByCategory("test")
        val sourceFiles = db.getFilesByCategory("source")
        if (testFiles.isEmpty()) return 0

        var count = 0
        val resolvedRoot = repoPath.toAbsolutePath().normalize()

        // Test-to-source ratio.
        val ratio = if (sourceFiles.isNotEmpty()) {
            "%.2f".format(testFiles.size.toDouble() / sourceFiles.size)
        } else "N/A"
        db.insertEvidence(taskId, EvidenceCategory.TEST_PATTERNS.name, "test_source_ratio", ratio)
        count++

        // Detect test frameworks by scanning content of a few test files.
        val frameworkSignals = mutableMapOf<String, Int>()
        val frameworkPatterns = mapOf(
            "JUnit" to Regex("""@Test|org\.junit"""),
            "TestNG" to Regex("""org\.testng"""),
            "pytest" to Regex("""import\s+pytest|@pytest"""),
            "unittest" to Regex("""import\s+unittest|unittest\.TestCase"""),
            "Jest" to Regex("""describe\s*\(|it\s*\(|expect\s*\("""),
            "Mocha" to Regex("""describe\s*\(|it\s*\(|chai"""),
            "RSpec" to Regex("""describe\s+|context\s+|it\s+['"]"""),
            "Go testing" to Regex("""func\s+Test\w+\(t\s+\*testing\.T\)"""),
            "Rust #[test]" to Regex("""#\[test]"""),
            "XCTest" to Regex("""XCTestCase|func\s+test\w+"""),
            "Kotest" to Regex("""io\.kotest|StringSpec|FunSpec|BehaviorSpec"""),
            "ScalaTest" to Regex("""org\.scalatest|FlatSpec|FunSuite""")
        )

        val sampleTestFiles = testFiles.take(20)
        for (file in sampleTestFiles) {
            val filePath = resolvedRoot.resolve(file.relativePath)
            if (!Files.isRegularFile(filePath)) continue
            val content = try {
                Files.readString(filePath)
            } catch (_: Exception) {
                continue
            }
            for ((framework, pattern) in frameworkPatterns) {
                if (pattern.containsMatchIn(content)) {
                    frameworkSignals[framework] = (frameworkSignals[framework] ?: 0) + 1
                }
            }
        }

        for ((framework, hits) in frameworkSignals.entries.sortedByDescending { it.value }) {
            db.insertEvidence(
                taskId,
                EvidenceCategory.TEST_PATTERNS.name,
                "test_framework",
                "$framework (found in $hits files)"
            )
            count++
        }

        return count
    }

    /**
     * Detects authentication and authorization patterns by scanning source files
     * for common auth-related identifiers and imports.
     */
    private fun detectAuthPatterns(taskId: String, db: Database, repoPath: Path): Int {
        val sourceFiles = db.getFilesByCategory("source")
        if (sourceFiles.isEmpty()) return 0

        val resolvedRoot = repoPath.toAbsolutePath().normalize()
        val authSignals = mutableMapOf<String, Int>()

        val authPatterns = mapOf(
            "JWT" to Regex("""jwt|jsonwebtoken|JwtToken|JWT_SECRET""", RegexOption.IGNORE_CASE),
            "OAuth" to Regex("""oauth|OAuth2|authorization_code|client_credentials""", RegexOption.IGNORE_CASE),
            "Session-based" to Regex("""session|cookie|express-session|HttpSession""", RegexOption.IGNORE_CASE),
            "API Key" to Regex("""api[_-]?key|x-api-key|apiKey""", RegexOption.IGNORE_CASE),
            "RBAC" to Regex("""role|permission|authorize|@Secured|@RolesAllowed|hasRole""", RegexOption.IGNORE_CASE),
            "Basic Auth" to Regex("""BasicAuth|basic\s+auth|httpBasic""", RegexOption.IGNORE_CASE)
        )

        // Sample a subset to avoid scanning the entire repo.
        val sampled = sourceFiles.filter { file ->
            val lower = file.relativePath.lowercase()
            lower.contains("auth") || lower.contains("security") ||
                lower.contains("login") || lower.contains("session") ||
                lower.contains("middleware") || lower.contains("guard")
        }.take(30).ifEmpty {
            sourceFiles.take(30)
        }

        for (file in sampled) {
            val filePath = resolvedRoot.resolve(file.relativePath)
            if (!Files.isRegularFile(filePath)) continue
            val content = try {
                Files.readString(filePath)
            } catch (_: Exception) {
                continue
            }
            for ((signal, pattern) in authPatterns) {
                if (pattern.containsMatchIn(content)) {
                    authSignals[signal] = (authSignals[signal] ?: 0) + 1
                }
            }
        }

        var count = 0
        for ((signal, hits) in authSignals.entries.sortedByDescending { it.value }) {
            db.insertEvidence(
                taskId,
                EvidenceCategory.AUTH_PATTERNS.name,
                "auth_pattern",
                "$signal (found in $hits files)"
            )
            count++
        }
        return count
    }

    /**
     * Detects API endpoints by scanning for route/endpoint declarations.
     */
    private fun detectApiEndpoints(taskId: String, db: Database, repoPath: Path): Int {
        val sourceFiles = db.getFilesByCategory("source")
        if (sourceFiles.isEmpty()) return 0

        val resolvedRoot = repoPath.toAbsolutePath().normalize()

        val endpointPatterns = mapOf(
            "REST annotations" to Regex("""@(?:Get|Post|Put|Delete|Patch)Mapping|@(?:GET|POST|PUT|DELETE|PATCH)"""),
            "Express routes" to Regex("""(?:app|router)\.\s*(?:get|post|put|delete|patch)\s*\("""),
            "Flask routes" to Regex("""@(?:app|blueprint)\.route\s*\("""),
            "FastAPI" to Regex("""@(?:app|router)\.(?:get|post|put|delete|patch)\s*\("""),
            "Go HTTP" to Regex("""http\.Handle(?:Func)?\s*\("""),
            "ASP.NET" to Regex("""\[Http(?:Get|Post|Put|Delete|Patch)\]"""),
            "Ktor" to Regex("""(?:get|post|put|delete|patch)\s*\(\s*["'/]""")
        )

        val detectedEndpointTypes = mutableMapOf<String, Int>()
        var filesWithEndpoints = 0

        // Scan source files, focusing on controller/route/handler files.
        val candidates = sourceFiles.filter { file ->
            val lower = file.relativePath.lowercase()
            lower.contains("controller") || lower.contains("route") ||
                lower.contains("handler") || lower.contains("endpoint") ||
                lower.contains("api") || lower.contains("resource")
        }.take(30).ifEmpty {
            sourceFiles.take(50)
        }

        for (file in candidates) {
            val filePath = resolvedRoot.resolve(file.relativePath)
            if (!Files.isRegularFile(filePath)) continue
            val content = try {
                Files.readString(filePath)
            } catch (_: Exception) {
                continue
            }
            var fileHasEndpoints = false
            for ((epType, pattern) in endpointPatterns) {
                val matchCount = pattern.findAll(content).count()
                if (matchCount > 0) {
                    detectedEndpointTypes[epType] = (detectedEndpointTypes[epType] ?: 0) + matchCount
                    fileHasEndpoints = true
                }
            }
            if (fileHasEndpoints) filesWithEndpoints++
        }

        var count = 0
        for ((epType, hits) in detectedEndpointTypes.entries.sortedByDescending { it.value }) {
            db.insertEvidence(
                taskId,
                EvidenceCategory.API_ENDPOINTS.name,
                "endpoint_style",
                "$epType ($hits occurrences)"
            )
            count++
        }
        if (filesWithEndpoints > 0) {
            db.insertEvidence(
                taskId,
                EvidenceCategory.API_ENDPOINTS.name,
                "files_with_endpoints",
                filesWithEndpoints.toString()
            )
            count++
        }
        return count
    }

    /**
     * Detects CI/CD configuration files and identifies the pipeline tool.
     */
    private fun detectCiCdSignals(taskId: String, db: Database): Int {
        val runtimeFiles = db.getFilesByCategory("runtime")
        val configFiles = db.getFilesByCategory("config")
        val allRelevant = runtimeFiles + configFiles
        if (allRelevant.isEmpty()) return 0

        var count = 0
        val detectedSystems = mutableSetOf<String>()

        for (file in allRelevant) {
            val normalized = file.relativePath.replace('\\', '/')
            for ((marker, system) in CI_CD_MARKERS) {
                if (normalized.contains(marker) && system !in detectedSystems) {
                    detectedSystems.add(system)
                    db.insertEvidence(
                        taskId,
                        EvidenceCategory.CI_CD_SIGNALS.name,
                        "ci_cd_system",
                        system
                    )
                    count++
                }
            }
        }

        return count
    }

    /**
     * Detects runtime/deployment shape from Docker, Procfile, Terraform files.
     */
    private fun detectRuntimeShape(taskId: String, db: Database): Int {
        val runtimeFiles = db.getFilesByCategory("runtime")
        if (runtimeFiles.isEmpty()) return 0

        var count = 0
        val signals = mutableSetOf<String>()

        for (file in runtimeFiles) {
            val fileName = file.relativePath.substringAfterLast('/')
            val normalized = file.relativePath.replace('\\', '/')
            when {
                fileName.startsWith("Dockerfile") -> signals.add("Docker")
                fileName.startsWith("docker-compose") -> signals.add("Docker Compose")
                fileName.endsWith(".tf") -> signals.add("Terraform")
                fileName == "Procfile" -> signals.add("Heroku/Procfile")
                normalized.contains(".github/workflows") -> { /* already covered by CI/CD */ }
                fileName == "Jenkinsfile" -> { /* already covered by CI/CD */ }
                fileName == ".gitlab-ci.yml" -> { /* already covered by CI/CD */ }
            }
        }

        for (signal in signals) {
            db.insertEvidence(
                taskId,
                EvidenceCategory.RUNTIME_SHAPE.name,
                "runtime_signal",
                signal
            )
            count++
        }

        return count
    }

    /**
     * Detects IntelliJ extension points from the database.
     */
    private fun detectExtensionPoints(taskId: String, db: Database): Int {
        val eps = db.getAllExtensionPoints()
        if (eps.isEmpty()) return 0
        var count = 0
        for (ep in eps.take(30)) {
            val implCount = db.getImplementationCount(ep.id)
            db.insertEvidence(
                taskId,
                EvidenceCategory.EXTENSION_POINTS.name,
                "ep:${ep.qualifiedName}",
                "${ep.qualifiedName} ($implCount implementations)"
            )
            count++
        }
        return count
    }

    /**
     * Detects the IntelliJ module graph: module types and top modules.
     */
    private fun detectModuleGraph(taskId: String, db: Database): Int {
        val modules = db.getAllModules()
        if (modules.isEmpty()) return 0
        var count = 0
        // Record module types distribution
        val byType = modules.groupBy { it.moduleType ?: "unknown" }
        for ((type, mods) in byType) {
            db.insertEvidence(
                taskId,
                EvidenceCategory.MODULE_GRAPH.name,
                "type:$type",
                "${mods.size} modules of type $type"
            )
            count++
        }
        // Record top modules by file count
        val topModules = modules.sortedByDescending { it.fileCount }.take(10)
        for (mod in topModules) {
            db.insertEvidence(
                taskId,
                EvidenceCategory.MODULE_GRAPH.name,
                "module:${mod.name}",
                "${mod.name} (${mod.fileCount} files, type: ${mod.moduleType})"
            )
            count++
        }
        return count
    }

    /**
     * Detects IntelliJ service extension points.
     */
    private fun detectServices(taskId: String, db: Database): Int {
        val eps = db.getAllExtensionPoints()
        val serviceEps = eps.filter {
            it.qualifiedName.contains("service", ignoreCase = true) ||
                it.qualifiedName.contains("Service")
        }
        var count = 0
        for (ep in serviceEps.take(20)) {
            db.insertEvidence(
                taskId,
                EvidenceCategory.SERVICES.name,
                "service:${ep.qualifiedName}",
                ep.qualifiedName
            )
            count++
        }
        return count
    }

    /**
     * Detects PSI-related files in the repository.
     */
    private fun detectPsiPatterns(taskId: String, db: Database): Int {
        // Look for PSI-related files in the database
        val psiFiles = db.getAllFiles().filter { file ->
            file.relativePath.contains("psi", ignoreCase = true) ||
                file.relativePath.contains("parser", ignoreCase = true) ||
                file.relativePath.contains("lexer", ignoreCase = true)
        }.take(20)

        var count = 0
        for (file in psiFiles) {
            db.insertEvidence(
                taskId,
                EvidenceCategory.PSI_PATTERNS.name,
                "file:${file.relativePath}",
                "${file.relativePath} (${file.language})"
            )
            count++
        }
        return count
    }

    /**
     * Selects representative source files: a mix of the largest, the ones
     * with the most common language, and entry-point candidates.
     */
    private fun detectRepresentativeFiles(taskId: String, db: Database): Int {
        val sourceFiles = db.getFilesByCategory("source")
        if (sourceFiles.isEmpty()) return 0

        val selected = mutableSetOf<String>()

        // Entry-point candidates.
        val entryPointNames = setOf(
            "main", "index", "app", "server", "application", "program", "startup"
        )
        for (file in sourceFiles) {
            val baseName = file.relativePath
                .substringAfterLast('/')
                .substringBeforeLast('.')
                .lowercase()
            if (baseName in entryPointNames) {
                selected.add(file.relativePath)
            }
        }

        // Most common language files, pick a few from each.
        val byLanguage = sourceFiles.groupBy { it.language ?: "unknown" }
        val topLanguages = byLanguage.entries.sortedByDescending { it.value.size }.take(3)
        for ((_, files) in topLanguages) {
            // Pick the median-sized file as "representative".
            val sorted = files.sortedBy { it.sizeBytes ?: 0L }
            val median = sorted[sorted.size / 2]
            selected.add(median.relativePath)
        }

        // Largest files (they tend to be important).
        sourceFiles.sortedByDescending { it.sizeBytes ?: 0L }.take(3).forEach {
            selected.add(it.relativePath)
        }

        var count = 0
        for (path in selected.take(10)) {
            db.insertEvidence(
                taskId,
                EvidenceCategory.REPRESENTATIVE_FILES.name,
                "representative_file",
                path
            )
            count++
        }
        return count
    }
}
