package forge.retrieval

import forge.workspace.Database
import forge.workspace.FileRecord
import java.nio.file.Files
import java.nio.file.Path

/**
 * The result of mapping all import/dependency relationships in a repository.
 *
 * @property imports  file relative-path -> list of raw import strings found in that file.
 * @property keyModules  the most-frequently-imported packages or modules, ordered by
 *                       descending import count.
 */
data class DependencyMap(
    val imports: Map<String, List<String>>,
    val keyModules: List<String>
)

/**
 * Scans source files for import / require / include statements, builds a
 * dependency graph, identifies the most-imported modules, and persists
 * everything to the evidence table via [Database].
 */
class DependencyMapper {

    companion object {
        // ----- Per-language import patterns -----
        // Each entry maps a canonical language key to a list of regex patterns.
        // Every pattern must have at least one capturing group that extracts the
        // imported module/package path.

        private val IMPORT_PATTERNS: Map<String, List<Regex>> = mapOf(
            "kotlin" to listOf(
                Regex("""import\s+([\w.]+)""")
            ),
            "java" to listOf(
                Regex("""import\s+(?:static\s+)?([\w.]+)""")
            ),
            "python" to listOf(
                Regex("""from\s+([\w.]+)\s+import\s+[\w,\s]+"""),
                Regex("""import\s+([\w.]+)""")
            ),
            "typescript" to listOf(
                Regex("""import\s+.*?from\s+['"]([^'"]+)['"]"""),
                Regex("""import\s*\(\s*['"]([^'"]+)['"]\s*\)"""),
                Regex("""require\s*\(\s*['"]([^'"]+)['"]\s*\)""")
            ),
            "javascript" to listOf(
                Regex("""import\s+.*?from\s+['"]([^'"]+)['"]"""),
                Regex("""import\s*\(\s*['"]([^'"]+)['"]\s*\)"""),
                Regex("""require\s*\(\s*['"]([^'"]+)['"]\s*\)""")
            ),
            "go" to listOf(
                // Single-line: import "fmt"
                Regex("""import\s+"([\w./]+)""""),
                // Block import entries (one per line inside parentheses):
                Regex("""^\s*"([\w./]+)"\s*$""", RegexOption.MULTILINE)
            ),
            "rust" to listOf(
                Regex("""use\s+([\w:]+)""")
            ),
            "csharp" to listOf(
                Regex("""using\s+([\w.]+)\s*;""")
            ),
            "c" to listOf(
                Regex("""#include\s*[<"]([\w./]+)[>"]""")
            ),
            "cpp" to listOf(
                Regex("""#include\s*[<"]([\w./]+)[>"]""")
            ),
            "c-header" to listOf(
                Regex("""#include\s*[<"]([\w./]+)[>"]""")
            ),
            "cpp-header" to listOf(
                Regex("""#include\s*[<"]([\w./]+)[>"]""")
            ),
            "scala" to listOf(
                Regex("""import\s+([\w.{}_ ]+)""")
            ),
            "ruby" to listOf(
                Regex("""require\s+['"]([^'"]+)['"]"""),
                Regex("""require_relative\s+['"]([^'"]+)['"]""")
            ),
            "php" to listOf(
                Regex("""use\s+([\w\\]+)"""),
                Regex("""require(?:_once)?\s+['"]([^'"]+)['"]"""),
                Regex("""include(?:_once)?\s+['"]([^'"]+)['"]""")
            ),
            "swift" to listOf(
                Regex("""import\s+([\w.]+)""")
            )
        )

        /** Number of top modules to return in [DependencyMap.keyModules]. */
        private const val TOP_MODULES_COUNT = 20
    }

    /**
     * Scans all source files stored in [db], extracts import statements,
     * builds the full dependency map, identifies key modules, and saves
     * evidence records for the given [taskId].
     *
     * @return The aggregated [DependencyMap].
     */
    fun mapDependencies(
        db: Database,
        repoPath: Path,
        taskId: String = ""
    ): DependencyMap {
        val resolvedRoot = repoPath.toAbsolutePath().normalize()
        val allFiles = db.getAllFiles()

        // file relative path -> list of raw import strings
        val fileImports = mutableMapOf<String, MutableList<String>>()

        // global frequency count: imported module -> occurrence count
        val moduleCounts = mutableMapOf<String, Int>()

        for (file in allFiles) {
            if (file.category != "source" && file.category != "test") continue

            val lang = file.language ?: continue
            val patterns = IMPORT_PATTERNS[lang] ?: continue

            val filePath = resolvedRoot.resolve(file.relativePath)
            if (!Files.isRegularFile(filePath)) continue

            val content = try {
                Files.readString(filePath)
            } catch (_: Exception) {
                continue
            }

            val imports = extractImports(content, patterns)
            if (imports.isNotEmpty()) {
                fileImports[file.relativePath] = imports.toMutableList()

                for (imp in imports) {
                    val normalized = normalizeModule(imp, lang)
                    moduleCounts[normalized] = (moduleCounts[normalized] ?: 0) + 1
                }
            }
        }

        // Rank modules by import frequency and take the top N.
        val keyModules = moduleCounts.entries
            .sortedByDescending { it.value }
            .take(TOP_MODULES_COUNT)
            .map { it.key }

        // Persist to evidence table if a taskId is provided.
        if (taskId.isNotBlank()) {
            persistEvidence(db, taskId, fileImports, keyModules, moduleCounts)
        }

        return DependencyMap(
            imports = fileImports,
            keyModules = keyModules
        )
    }

    // -----------------------------------------------------------------------
    //  Import extraction
    // -----------------------------------------------------------------------

    /**
     * Applies each [patterns] to [content] and collects distinct import strings.
     */
    private fun extractImports(content: String, patterns: List<Regex>): List<String> {
        val found = linkedSetOf<String>()
        for (pattern in patterns) {
            for (match in pattern.findAll(content)) {
                val importPath = match.groupValues.getOrNull(1)?.trim()
                if (!importPath.isNullOrBlank()) {
                    found.add(importPath)
                }
            }
        }
        return found.toList()
    }

    /**
     * Normalizes an import string to a canonical module identifier.
     * For JVM languages, strips the final class/function segment so that
     * `com.example.util.StringHelper` becomes `com.example.util`.
     * For JS/TS, strips relative path prefixes.
     */
    private fun normalizeModule(raw: String, language: String): String {
        return when (language) {
            "kotlin", "java", "scala" -> {
                val lastDot = raw.lastIndexOf('.')
                if (lastDot > 0) raw.substring(0, lastDot) else raw
            }

            "typescript", "javascript" -> {
                // Remove leading ./ or ../
                raw.removePrefix("./").removePrefix("../")
            }

            "rust" -> {
                // use std::collections::HashMap  -> std::collections
                val lastColon = raw.lastIndexOf("::")
                if (lastColon > 0) raw.substring(0, lastColon) else raw
            }

            "python" -> {
                val lastDot = raw.lastIndexOf('.')
                if (lastDot > 0) raw.substring(0, lastDot) else raw
            }

            "csharp" -> {
                val lastDot = raw.lastIndexOf('.')
                if (lastDot > 0) raw.substring(0, lastDot) else raw
            }

            "go" -> {
                // Already a full package path; keep as-is.
                raw
            }

            else -> raw
        }
    }

    // -----------------------------------------------------------------------
    //  Evidence persistence
    // -----------------------------------------------------------------------

    private fun persistEvidence(
        db: Database,
        taskId: String,
        fileImports: Map<String, List<String>>,
        keyModules: List<String>,
        moduleCounts: Map<String, Int>
    ) {
        // Save key modules.
        for ((index, module) in keyModules.withIndex()) {
            val count = moduleCounts[module] ?: 0
            db.insertEvidence(
                taskId = taskId,
                category = "KEY_MODULES",
                key = "module_rank_${index + 1}",
                value = "$module (imported $count times)"
            )
        }

        // Save per-file dependency summary for files with many imports (> 5),
        // which are likely integration points.
        val integrationFiles = fileImports.entries
            .filter { it.value.size > 5 }
            .sortedByDescending { it.value.size }
            .take(20)

        for ((filePath, imports) in integrationFiles) {
            db.insertEvidence(
                taskId = taskId,
                category = "INTEGRATION_POINTS",
                key = filePath,
                value = "${imports.size} imports: ${imports.take(10).joinToString(", ")}"
            )
        }

        // Save total dependency stats.
        db.insertEvidence(
            taskId = taskId,
            category = "DEPENDENCIES",
            key = "total_files_with_imports",
            value = fileImports.size.toString()
        )
        db.insertEvidence(
            taskId = taskId,
            category = "DEPENDENCIES",
            key = "unique_modules_imported",
            value = moduleCounts.size.toString()
        )
    }
}
