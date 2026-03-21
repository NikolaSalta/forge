package forge.retrieval

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.exists

/**
 * Discovers project modules in non-IntelliJ repos by looking for
 * build-file markers (package.json, pom.xml, build.gradle, etc.).
 */
object GenericModuleDiscovery {

    data class DiscoveredModule(
        val name: String,
        val path: String,
        val moduleType: String,   // "nodejs", "maven", "gradle", "python", "docker", "generic"
        val language: String      // "typescript", "java", "kotlin", "python", "unknown"
    )

    private val BUILD_MARKERS = listOf(
        "package.json"       to ("nodejs" to "typescript"),
        "pom.xml"            to ("maven" to "java"),
        "build.gradle"       to ("gradle" to "java"),
        "build.gradle.kts"   to ("gradle" to "kotlin"),
        "Cargo.toml"         to ("cargo" to "rust"),
        "go.mod"             to ("gomod" to "go"),
        "pyproject.toml"     to ("python" to "python"),
        "setup.py"           to ("python" to "python"),
        "requirements.txt"   to ("python" to "python"),
        "Gemfile"            to ("ruby" to "ruby"),
        "composer.json"      to ("composer" to "php"),
    )

    private val SKIP_DIRS = setOf(
        "node_modules", ".git", ".svn", ".hg", "__pycache__",
        "build", "dist", "target", ".gradle", ".idea", ".vscode",
        "vendor", ".tox", ".mypy_cache", ".pytest_cache"
    )

    /**
     * Walk the repo up to 3 levels deep and find directories that contain
     * a build-file marker.  The root itself is also checked.
     */
    fun discover(repoRoot: Path, maxDepth: Int = 3): List<DiscoveredModule> {
        val modules = mutableListOf<DiscoveredModule>()
        val seen = mutableSetOf<String>()

        walkForModules(repoRoot, repoRoot, 0, maxDepth, modules, seen)
        return modules
    }

    private fun walkForModules(
        dir: Path,
        root: Path,
        depth: Int,
        maxDepth: Int,
        out: MutableList<DiscoveredModule>,
        seen: MutableSet<String>
    ) {
        if (depth > maxDepth) return
        if (dir.name in SKIP_DIRS) return

        // Check each marker in this directory
        for ((marker, typePair) in BUILD_MARKERS) {
            val markerFile = dir.resolve(marker)
            if (markerFile.exists()) {
                val relPath = root.relativize(dir).toString().ifEmpty { "." }
                val key = "$relPath:${typePair.first}"
                if (key !in seen) {
                    seen.add(key)
                    val name = if (relPath == ".") root.name else dir.name
                    out.add(DiscoveredModule(
                        name = name.toString(),
                        path = relPath,
                        moduleType = typePair.first,
                        language = typePair.second
                    ))
                }
                // Don't keep scanning children for same type
                break
            }
        }

        // Recurse into subdirectories
        try {
            Files.newDirectoryStream(dir).use { stream ->
                for (child in stream) {
                    if (Files.isDirectory(child) && child.name !in SKIP_DIRS) {
                        walkForModules(child, root, depth + 1, maxDepth, out, seen)
                    }
                }
            }
        } catch (_: Exception) { }
    }
}
