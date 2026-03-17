package forge.retrieval

import forge.WorkspaceConfig
import forge.intellij.IntelliJModuleResolver
import forge.workspace.Database
import forge.workspace.FileRecord
import forge.workspace.ModuleRecord
import java.io.IOException
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Scan stages controlling which file categories to discover.
 */
enum class ScanStage {
    ROOT,
    BUILD,
    SOURCE,
    TEST,
    RUNTIME
}

/**
 * Per-stage scan statistics.
 */
data class StageResult(val filesScanned: Int, val totalBytes: Long)

/**
 * Aggregate result returned from a full repository scan.
 */
data class ScanResult(val filesScanned: Int = 0, val stages: Map<ScanStage, StageResult>)

/**
 * Represents a file discovered during scanning, before it is persisted.
 */
data class ScannedFile(
    val path: Path,
    val relativePath: String,
    val sizeBytes: Long,
    val language: String,
    val category: String,
    val stage: ScanStage
)

/**
 * Five-stage repository scanner using NIO [Files.walkFileTree] for
 * memory-efficient traversal. Each stage targets specific file patterns
 * and categories. Discovered files are persisted to [Database] immediately
 * so that the working set is never held entirely in RAM.
 */
class RepoScanner(
    private val config: WorkspaceConfig = WorkspaceConfig()
) {
    companion object {
        /** Directories that are always skipped during traversal. */
        private val DEFAULT_IGNORE_DIRS = setOf(
            "node_modules", ".git", "__pycache__", "build", "dist",
            ".idea", ".vscode", "target", "vendor", ".gradle", "bin", "obj"
        )

        // ----- ROOT stage patterns (root-level only) -----
        private val ROOT_EXACT = setOf(
            ".gitignore", "Makefile"
        )
        private val ROOT_PREFIXES = listOf("README", "LICENSE")
        private val ROOT_EXTENSIONS = setOf("md")

        // ----- BUILD stage file names / globs -----
        private val BUILD_EXACT = setOf(
            "pom.xml", "package.json", "Cargo.toml", "go.mod",
            "pyproject.toml", "CMakeLists.txt", "build.sbt",
            "Gemfile", "composer.json"
        )
        private val BUILD_PREFIXES = listOf("build.gradle")
        private val BUILD_EXTENSIONS = setOf("sln", "csproj")

        // ----- SOURCE stage extensions -----
        private val SOURCE_EXTENSIONS = setOf(
            "kt", "java", "ts", "tsx", "js", "jsx",
            "py", "go", "rs", "cs", "cpp", "c", "h", "hpp",
            "scala", "rb", "php", "swift"
        )

        // ----- TEST stage directory markers and name patterns -----
        private val TEST_DIR_NAMES = setOf("test", "tests", "spec", "__tests__")
        private val TEST_NAME_PREFIXES = listOf("test_")
        private val TEST_NAME_SUFFIXES = listOf("Test.", "_test.", "Spec.")

        // ----- RUNTIME stage -----
        private val RUNTIME_PREFIXES = listOf("Dockerfile", "docker-compose")
        private val RUNTIME_EXACT = setOf("Jenkinsfile", ".gitlab-ci.yml", "Procfile")
        private val RUNTIME_EXTENSIONS = setOf("tf")
        private val RUNTIME_PATH_CONTAINS = listOf(".github/workflows/")

        // ----- Language detection by extension -----
        private val EXTENSION_TO_LANGUAGE: Map<String, String> = mapOf(
            "kt" to "kotlin", "kts" to "kotlin",
            "java" to "java",
            "ts" to "typescript", "tsx" to "typescript",
            "js" to "javascript", "jsx" to "javascript",
            "py" to "python",
            "go" to "go",
            "rs" to "rust",
            "cs" to "csharp",
            "cpp" to "cpp", "cc" to "cpp", "cxx" to "cpp",
            "c" to "c",
            "h" to "c-header", "hpp" to "cpp-header",
            "scala" to "scala",
            "rb" to "ruby",
            "php" to "php",
            "swift" to "swift",
            "md" to "markdown",
            "yml" to "yaml", "yaml" to "yaml",
            "json" to "json",
            "xml" to "xml",
            "toml" to "toml",
            "gradle" to "gradle",
            "tf" to "hcl",
            "sh" to "shell", "bash" to "shell",
            "sql" to "sql",
            "html" to "html", "htm" to "html",
            "css" to "css", "scss" to "scss"
        )
    }

    /**
     * Scans [repoPath] for files matching the requested [stages],
     * persisting every discovered file to [db]. Files in directories
     * matching [ignorePatterns] (plus the built-in ignore list) are skipped,
     * as are files larger than [config.maxFileSizeKb].
     */
    fun scan(
        repoPath: Path,
        db: Database,
        stages: Set<ScanStage> = ScanStage.entries.toSet(),
        ignorePatterns: Set<String> = emptySet()
    ): ScanResult {
        val resolvedRoot = repoPath.toAbsolutePath().normalize()
        val ignoreDirs = DEFAULT_IGNORE_DIRS + ignorePatterns
        val maxBytes = config.maxFileSizeKb.toLong() * 1024L

        // Accumulators per stage
        val counters = stages.associateWith { AtomicInteger(0) }
        val byteTotals = stages.associateWith { AtomicLong(0L) }

        Files.walkFileTree(resolvedRoot, object : SimpleFileVisitor<Path>() {

            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                val dirName = dir.fileName?.toString() ?: return FileVisitResult.CONTINUE
                if (dirName in ignoreDirs) {
                    return FileVisitResult.SKIP_SUBTREE
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (!attrs.isRegularFile) return FileVisitResult.CONTINUE
                val size = attrs.size()
                if (size > maxBytes) return FileVisitResult.CONTINUE

                val relativePath = resolvedRoot.relativize(file).toString()
                val fileName = file.fileName?.toString() ?: return FileVisitResult.CONTINUE
                val extension = extensionOf(fileName)

                // Attempt to classify the file into one of the requested stages.
                // A file is assigned to the FIRST matching stage in priority order.
                val matchedStage = classifyFile(
                    stages, resolvedRoot, file, relativePath, fileName, extension
                ) ?: return FileVisitResult.CONTINUE

                val language = detectLanguage(fileName, extension)
                val category = categoryForStage(matchedStage, relativePath, fileName)

                db.insertFile(
                    path = file.toAbsolutePath().toString(),
                    relativePath = relativePath,
                    language = language,
                    sizeBytes = size,
                    lineCount = null,
                    sha256 = null,
                    category = category
                )

                counters[matchedStage]?.incrementAndGet()
                byteTotals[matchedStage]?.addAndGet(size)

                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException?): FileVisitResult {
                // Silently skip files we cannot read (permission errors, broken symlinks, etc.)
                return FileVisitResult.CONTINUE
            }
        })

        val stageResults = stages.associateWith { stage ->
            StageResult(
                filesScanned = counters[stage]?.get() ?: 0,
                totalBytes = byteTotals[stage]?.get() ?: 0L
            )
        }
        return ScanResult(stages = stageResults)
    }

    // -----------------------------------------------------------------------
    //  Classification helpers
    // -----------------------------------------------------------------------

    /**
     * Returns the [ScanStage] that a file belongs to, or null if it
     * does not match any requested stage.
     */
    private fun classifyFile(
        stages: Set<ScanStage>,
        root: Path,
        file: Path,
        relativePath: String,
        fileName: String,
        extension: String
    ): ScanStage? {
        // TEST must be checked before SOURCE because test files also have source extensions.
        if (ScanStage.TEST in stages && matchesTest(relativePath, fileName)) return ScanStage.TEST
        if (ScanStage.ROOT in stages && matchesRoot(root, file, fileName, extension)) return ScanStage.ROOT
        if (ScanStage.BUILD in stages && matchesBuild(fileName, extension)) return ScanStage.BUILD
        if (ScanStage.RUNTIME in stages && matchesRuntime(relativePath, fileName, extension)) return ScanStage.RUNTIME
        if (ScanStage.SOURCE in stages && matchesSource(extension)) return ScanStage.SOURCE
        return null
    }

    private fun matchesRoot(root: Path, file: Path, fileName: String, extension: String): Boolean {
        // ROOT stage: only files directly in the repository root directory.
        if (file.parent != root) return false
        if (fileName in ROOT_EXACT) return true
        if (ROOT_PREFIXES.any { fileName.startsWith(it) }) return true
        if (extension in ROOT_EXTENSIONS) return true
        return false
    }

    private fun matchesBuild(fileName: String, extension: String): Boolean {
        if (fileName in BUILD_EXACT) return true
        if (BUILD_PREFIXES.any { fileName.startsWith(it) }) return true
        if (extension in BUILD_EXTENSIONS) return true
        return false
    }

    private fun matchesSource(extension: String): Boolean {
        return extension in SOURCE_EXTENSIONS
    }

    private fun matchesTest(relativePath: String, fileName: String): Boolean {
        // Check if the file sits inside a well-known test directory.
        val normalizedPath = relativePath.replace('\\', '/')
        val segments = normalizedPath.split('/')
        if (segments.any { it in TEST_DIR_NAMES }) {
            // Only count as test if it also has a source-code extension.
            val ext = extensionOf(fileName)
            if (ext in SOURCE_EXTENSIONS) return true
        }

        // Check filename patterns.
        if (TEST_NAME_PREFIXES.any { fileName.startsWith(it) }) return true
        if (TEST_NAME_SUFFIXES.any { fileName.contains(it) }) return true

        return false
    }

    private fun matchesRuntime(relativePath: String, fileName: String, extension: String): Boolean {
        if (fileName in RUNTIME_EXACT) return true
        if (RUNTIME_PREFIXES.any { fileName.startsWith(it) }) return true
        if (extension in RUNTIME_EXTENSIONS) return true
        val normalizedPath = relativePath.replace('\\', '/')
        if (RUNTIME_PATH_CONTAINS.any { normalizedPath.contains(it) }) return true
        return false
    }

    // -----------------------------------------------------------------------
    //  Utility helpers
    // -----------------------------------------------------------------------

    private fun extensionOf(fileName: String): String {
        val dotIndex = fileName.lastIndexOf('.')
        return if (dotIndex >= 0 && dotIndex < fileName.length - 1) {
            fileName.substring(dotIndex + 1).lowercase()
        } else ""
    }

    private fun detectLanguage(fileName: String, extension: String): String {
        // Try extension first, then special file names.
        EXTENSION_TO_LANGUAGE[extension]?.let { return it }

        return when {
            fileName == "Makefile" -> "makefile"
            fileName == "Dockerfile" || fileName.startsWith("Dockerfile.") -> "dockerfile"
            fileName == "Jenkinsfile" -> "groovy"
            fileName == "Gemfile" || fileName == "Rakefile" -> "ruby"
            fileName == "Procfile" -> "procfile"
            fileName.endsWith(".gradle.kts") -> "kotlin"
            else -> "unknown"
        }
    }

    private fun categoryForStage(stage: ScanStage, relativePath: String, fileName: String): String {
        return when (stage) {
            ScanStage.ROOT -> if (extensionOf(fileName) == "md" ||
                fileName.startsWith("README") ||
                fileName.startsWith("LICENSE")
            ) "docs" else "config"

            ScanStage.BUILD -> "build"
            ScanStage.SOURCE -> "source"
            ScanStage.TEST -> "test"
            ScanStage.RUNTIME -> "runtime"
        }
    }

    // -----------------------------------------------------------------------
    //  Phase 3-4: Parallel and incremental scanning methods
    // -----------------------------------------------------------------------

    /**
     * Phase 1: Discover IntelliJ modules and persist to DB.
     */
    fun discoverModules(
        repoPath: Path,
        repoId: Int,
        db: Database,
        moduleResolver: IntelliJModuleResolver
    ): List<ModuleRecord> {
        val modules = moduleResolver.discoverModules()
        moduleResolver.persistToDatabase(modules, repoId, db)
        return db.getModulesByRepo(repoId)
    }

    /**
     * Phase 2: Parallel file scanning across modules.
     * Each module is scanned in its own thread, with batch DB insertions.
     */
    fun scanModulesParallel(
        repoPath: Path,
        modules: List<ModuleRecord>,
        repoId: Int,
        db: Database,
        ignorePatterns: Set<String>,
        parallelThreads: Int = Runtime.getRuntime().availableProcessors(),
        batchSize: Int = 500
    ): ScanResult {
        val totalFiles = AtomicInteger(0)
        val executor = Executors.newFixedThreadPool(parallelThreads.coerceIn(1, 16))

        try {
            val futures = mutableListOf<Future<*>>()
            for (module in modules) {
                val modulePath = Path.of(module.path)
                if (!Files.exists(modulePath)) continue

                futures.add(executor.submit {
                    val batch = mutableListOf<Database.FileInsertData>()
                    var moduleFileCount = 0

                    try {
                        Files.walkFileTree(modulePath, object : SimpleFileVisitor<Path>() {
                            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                                val dirName = dir.fileName?.toString() ?: ""
                                if (dirName in ignorePatterns || dirName.startsWith(".")) {
                                    return FileVisitResult.SKIP_SUBTREE
                                }
                                return FileVisitResult.CONTINUE
                            }

                            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                                if (attrs.size() > 500 * 1024) return FileVisitResult.CONTINUE // Skip files > 500KB

                                val fileName = file.fileName?.toString() ?: return FileVisitResult.CONTINUE
                                val ext = extensionOf(fileName)
                                if (ext !in SOURCE_EXTENSIONS) return FileVisitResult.CONTINUE

                                val relativePath = repoPath.relativize(file).toString().replace('\\', '/')
                                val language = detectLanguage(fileName, ext)
                                val category = classifyFileSimple(relativePath, fileName)
                                val lineCount = try { Files.readAllLines(file).size } catch (_: Exception) { 0 }

                                batch.add(Database.FileInsertData(
                                    path = file.toAbsolutePath().toString(),
                                    relativePath = relativePath,
                                    language = language,
                                    sizeBytes = attrs.size(),
                                    lineCount = lineCount,
                                    sha256 = null,
                                    category = category,
                                    repoId = repoId,
                                    moduleId = module.id,
                                    scannedAt = java.time.Instant.now().toString()
                                ))

                                if (batch.size >= batchSize) {
                                    db.insertFilesBatch(batch.toList())
                                    totalFiles.addAndGet(batch.size)
                                    batch.clear()
                                }

                                moduleFileCount++
                                return FileVisitResult.CONTINUE
                            }

                            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                                return FileVisitResult.CONTINUE
                            }
                        })
                    } catch (_: Exception) {
                        // Skip modules that fail to scan
                    }

                    // Flush remaining batch
                    if (batch.isNotEmpty()) {
                        db.insertFilesBatch(batch.toList())
                        totalFiles.addAndGet(batch.size)
                    }

                    // Update module file count
                    db.updateModuleFileCount(module.id, moduleFileCount)
                })
            }

            // Wait for all to complete
            for (future in futures) {
                try { future.get() } catch (_: Exception) {}
            }
        } finally {
            executor.shutdown()
        }

        return ScanResult(
            filesScanned = totalFiles.get(),
            stages = emptyMap()
        )
    }

    /**
     * Phase 3: Incremental scanning via git diff.
     * Only processes files changed since the last scan.
     */
    fun scanIncremental(
        repoPath: Path,
        repoId: Int,
        db: Database,
        previousCommitSha: String,
        ignorePatterns: Set<String>,
        batchSize: Int = 500
    ): ScanResult {
        val currentSha = getCurrentCommitSha(repoPath) ?: return ScanResult(0, emptyMap())

        if (currentSha == previousCommitSha) {
            return ScanResult(0, emptyMap()) // No changes
        }

        val process = ProcessBuilder("git", "diff", "--name-status", "$previousCommitSha..$currentSha")
            .directory(repoPath.toFile())
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()

        val added = mutableListOf<String>()
        val modified = mutableListOf<String>()
        val deleted = mutableListOf<String>()

        for (line in output.lines()) {
            if (line.isBlank()) continue
            val parts = line.split("\t", limit = 2)
            if (parts.size < 2) continue
            val status = parts[0].trim()
            val filePath = parts[1].trim()

            when {
                status.startsWith("A") -> added.add(filePath)
                status.startsWith("M") -> modified.add(filePath)
                status.startsWith("D") -> deleted.add(filePath)
            }
        }

        // Delete removed files
        for (path in deleted) {
            db.deleteFile(repoPath.resolve(path).toAbsolutePath().toString())
        }

        // Add/update changed files in batches
        val changedPaths = (added + modified)
        val batch = mutableListOf<Database.FileInsertData>()
        var scannedCount = 0

        for (relativePath in changedPaths) {
            val file = repoPath.resolve(relativePath)
            if (!Files.exists(file)) continue

            val fileName = file.fileName?.toString() ?: continue
            val ext = extensionOf(fileName)
            if (ext !in SOURCE_EXTENSIONS) continue

            val shouldIgnore = ignorePatterns.any { relativePath.contains(it) }
            if (shouldIgnore) continue

            val attrs = try { Files.readAttributes(file, BasicFileAttributes::class.java) } catch (_: Exception) { continue }
            val language = detectLanguage(fileName, ext)
            val category = classifyFileSimple(relativePath, fileName)
            val lineCount = try { Files.readAllLines(file).size } catch (_: Exception) { 0 }

            batch.add(Database.FileInsertData(
                path = file.toAbsolutePath().toString(),
                relativePath = relativePath.replace('\\', '/'),
                language = language,
                sizeBytes = attrs.size(),
                lineCount = lineCount,
                sha256 = null,
                category = category,
                repoId = repoId,
                moduleId = null,
                scannedAt = java.time.Instant.now().toString()
            ))

            if (batch.size >= batchSize) {
                db.insertFilesBatch(batch.toList())
                scannedCount += batch.size
                batch.clear()
            }
        }

        if (batch.isNotEmpty()) {
            db.insertFilesBatch(batch.toList())
            scannedCount += batch.size
        }

        db.updateRepoScan(repoId, currentSha)

        return ScanResult(scannedCount, emptyMap())
    }

    private fun getCurrentCommitSha(repoPath: Path): String? {
        return try {
            val process = ProcessBuilder("git", "rev-parse", "HEAD")
                .directory(repoPath.toFile())
                .redirectErrorStream(true)
                .start()
            val sha = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            sha.takeIf { it.length >= 7 }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Simplified file classification for parallel/incremental scanning.
     * Uses path and name heuristics to determine category without requiring
     * the full stage-based classification.
     */
    private fun classifyFileSimple(relativePath: String, fileName: String): String {
        val normalizedPath = relativePath.replace('\\', '/')
        val segments = normalizedPath.split('/')
        if (segments.any { it in TEST_DIR_NAMES }) return "test"
        if (TEST_NAME_PREFIXES.any { fileName.startsWith(it) }) return "test"
        if (TEST_NAME_SUFFIXES.any { fileName.contains(it) }) return "test"
        if (fileName in BUILD_EXACT || BUILD_PREFIXES.any { fileName.startsWith(it) }) return "build"
        if (RUNTIME_PREFIXES.any { fileName.startsWith(it) } || fileName in RUNTIME_EXACT) return "runtime"
        if (RUNTIME_PATH_CONTAINS.any { normalizedPath.contains(it) }) return "runtime"
        return "source"
    }
}
