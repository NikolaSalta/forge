package forge.intellij

import forge.workspace.Database
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Represents a discovered IntelliJ Platform module with its metadata.
 */
data class IntelliJModule(
    val name: String,
    val path: Path,
    val pluginXmlPath: Path?,
    val imlPaths: List<Path>,
    val moduleType: ModuleType,
    val pluginData: PluginXmlData?,
    val dependencies: List<String>
)

/**
 * Discovers IntelliJ Platform modules by scanning for plugin.xml, .iml files,
 * and build configuration files within a repository.
 *
 * The discovery algorithm:
 * 1. Walk the repo root finding plugin.xml files (META-INF/plugin.xml)
 * 2. Walk finding .iml files
 * 3. For each marker, determine module root directory
 * 4. Classify module type by path pattern
 * 5. Parse plugin.xml for extension points, services, depends
 * 6. Build dependency graph
 *
 * @param repoRoot absolute path to the repository root
 */
class IntelliJModuleResolver(private val repoRoot: Path) {

    private val parser = PluginXmlParser()
    private val json = Json { prettyPrint = false }

    /**
     * Discover all IntelliJ modules in the repository.
     *
     * @return list of discovered modules with parsed metadata
     */
    fun discoverModules(): List<IntelliJModule> {
        val pluginXmlFiles = mutableListOf<Path>()
        val imlFiles = mutableListOf<Path>()

        // Phase 1: Find all marker files
        Files.walkFileTree(repoRoot, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                val dirName = dir.fileName?.toString() ?: ""
                if (dirName in IntelliJPatterns.INTELLIJ_SKIP_DIRS) {
                    return FileVisitResult.SKIP_SUBTREE
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val fileName = file.fileName.toString()
                when {
                    fileName == "plugin.xml" && file.parent?.fileName?.toString() == "META-INF" -> {
                        pluginXmlFiles.add(file)
                    }
                    fileName.endsWith(".iml") -> {
                        imlFiles.add(file)
                    }
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult {
                return FileVisitResult.CONTINUE
            }
        })

        // Phase 2: Group .iml files by their module root
        // Module root is typically parent of .iml file, or parent of parent if .iml is in .idea/
        val imlByModuleRoot = mutableMapOf<Path, MutableList<Path>>()
        for (iml in imlFiles) {
            val moduleRoot = resolveModuleRootFromIml(iml)
            imlByModuleRoot.getOrPut(moduleRoot) { mutableListOf() }.add(iml)
        }

        // Phase 3: Build module map from plugin.xml files
        val modules = mutableMapOf<String, IntelliJModule>()

        for (pluginXml in pluginXmlFiles) {
            val moduleRoot = resolveModuleRootFromPluginXml(pluginXml)
            val relativePath = repoRoot.relativize(moduleRoot).toString()
            val moduleName = relativePath.replace('\\', '/')

            if (moduleName.isEmpty() || moduleName == ".") continue

            val pluginData = parser.parse(pluginXml)
            val moduleType = IntelliJPatterns.classifyModuleType(moduleName)
            val imlsForModule = imlByModuleRoot[moduleRoot] ?: emptyList()

            // Collect dependencies from plugin.xml <depends> + .iml module dependencies
            val deps = mutableListOf<String>()
            deps.addAll(pluginData.depends)
            for (iml in imlsForModule) {
                deps.addAll(parseImlDependencies(iml))
            }

            modules[moduleName] = IntelliJModule(
                name = moduleName,
                path = moduleRoot,
                pluginXmlPath = pluginXml,
                imlPaths = imlsForModule,
                moduleType = moduleType,
                pluginData = pluginData,
                dependencies = deps.distinct()
            )
        }

        // Phase 4: Add modules from .iml files that don't have plugin.xml
        // (Only add top-level directories that aren't already covered)
        for ((moduleRoot, imls) in imlByModuleRoot) {
            val relativePath = repoRoot.relativize(moduleRoot).toString().replace('\\', '/')
            if (relativePath.isEmpty() || relativePath == "." || modules.containsKey(relativePath)) continue

            // Skip .idea directory iml files
            if (relativePath.contains(".idea")) continue

            val moduleType = IntelliJPatterns.classifyModuleType(relativePath)
            val deps = imls.flatMap { parseImlDependencies(it) }.distinct()

            modules[relativePath] = IntelliJModule(
                name = relativePath,
                path = moduleRoot,
                pluginXmlPath = null,
                imlPaths = imls,
                moduleType = moduleType,
                pluginData = null,
                dependencies = deps
            )
        }

        return modules.values.sortedBy { it.name }
    }

    /**
     * Parse .iml file for module dependencies.
     */
    fun parseImlDependencies(imlPath: Path): List<String> {
        return try {
            val content = Files.readString(imlPath)
            val deps = mutableListOf<String>()
            // Match <orderEntry type="module" module-name="..."/>
            val modulePattern = Regex("""<orderEntry\s+type="module"\s+module-name="([^"]+)"""")
            for (match in modulePattern.findAll(content)) {
                deps.add(match.groupValues[1])
            }
            deps
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Persist discovered modules to the workspace database.
     */
    fun persistToDatabase(modules: List<IntelliJModule>, repoId: Int, db: Database) {
        for (module in modules) {
            val depsJson = if (module.dependencies.isNotEmpty()) {
                json.encodeToString(module.dependencies)
            } else null

            val moduleId = db.insertModule(
                repoId = repoId,
                name = module.name,
                path = module.path.toString(),
                pluginXml = module.pluginXmlPath?.toString(),
                moduleType = module.moduleType.name,
                dependencies = depsJson
            )

            if (moduleId > 0 && module.pluginData != null) {
                // Persist extension points
                for (ep in module.pluginData.extensionPoints) {
                    db.insertExtensionPoint(
                        moduleId = moduleId,
                        qualifiedName = ep.qualifiedName,
                        interfaceFqn = ep.interfaceFqn,
                        beanClass = ep.beanClass,
                        area = ep.area,
                        description = null
                    )
                }
            }
        }
    }

    /**
     * Resolve the module root directory from a plugin.xml path.
     *
     * plugin.xml is typically at:
     * - module/resources/META-INF/plugin.xml -> module root = module/
     * - module/src/META-INF/plugin.xml -> module root = module/
     * - module/src/main/resources/META-INF/plugin.xml -> module root = module/
     * - module/META-INF/plugin.xml -> module root = module/
     */
    private fun resolveModuleRootFromPluginXml(pluginXml: Path): Path {
        var current = pluginXml.parent // META-INF/
        if (current.fileName.toString() == "META-INF") {
            current = current.parent // resources/ or src/ or module/
        }
        val dirName = current.fileName?.toString() ?: ""
        if (dirName == "resources" || dirName == "src") {
            current = current.parent
            // Check one more level: src/main/resources -> need to go up one more
            val parentName = current.fileName?.toString() ?: ""
            if (parentName == "main") {
                current = current.parent // src/
                val grandParentName = current.fileName?.toString() ?: ""
                if (grandParentName == "src") {
                    current = current.parent
                }
            }
        }
        return current
    }

    /**
     * Resolve module root from .iml path.
     * .iml files are typically at module root level or in .idea/ directory.
     */
    private fun resolveModuleRootFromIml(imlPath: Path): Path {
        val parent = imlPath.parent
        val parentName = parent.fileName?.toString() ?: ""
        // If .iml is in .idea/modules/ or .idea/, resolve to project root
        if (parentName == ".idea" || parentName == "modules") {
            return if (parentName == "modules") parent.parent.parent else parent.parent
        }
        return parent
    }
}
