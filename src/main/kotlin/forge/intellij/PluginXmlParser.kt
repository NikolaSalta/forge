package forge.intellij

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Parsed data from a plugin.xml file.
 */
data class PluginXmlData(
    val id: String?,
    val name: String?,
    val depends: List<String>,
    val extensionPoints: List<ExtensionPointDecl>,
    val services: List<ServiceDecl>,
    val extensions: List<ExtensionImpl>
)

data class ExtensionPointDecl(
    val qualifiedName: String,
    val interfaceFqn: String?,
    val beanClass: String?,
    val area: String?
)

data class ServiceDecl(
    val interfaceFqn: String?,
    val implementationFqn: String,
    val level: String
)

data class ExtensionImpl(
    val extensionPointName: String,
    val implementationFqn: String
)

/**
 * Parser for IntelliJ plugin.xml descriptor files.
 *
 * Uses javax.xml.parsers (JDK built-in) — no additional dependencies.
 * Tolerant of malformed XML (catches and returns partial results).
 */
class PluginXmlParser {

    private val factory = DocumentBuilderFactory.newInstance().apply {
        // Disable DTD loading to avoid network calls and speed up parsing
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    }

    /**
     * Parse a plugin.xml file and extract all relevant declarations.
     */
    fun parse(pluginXmlPath: Path): PluginXmlData {
        return try {
            Files.newInputStream(pluginXmlPath).use { stream ->
                parseStream(stream)
            }
        } catch (e: Exception) {
            // Return empty data for malformed XML
            PluginXmlData(
                id = null, name = null,
                depends = emptyList(),
                extensionPoints = emptyList(),
                services = emptyList(),
                extensions = emptyList()
            )
        }
    }

    private fun parseStream(stream: InputStream): PluginXmlData {
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(stream)
        doc.documentElement.normalize()

        val root = doc.documentElement

        val id = getTextContent(root, "id")
        val name = getTextContent(root, "name")
        val depends = parseDepends(root)
        val extensionPoints = parseExtensionPoints(root)
        val services = parseServices(root)
        val extensions = parseExtensions(root)

        return PluginXmlData(
            id = id,
            name = name,
            depends = depends,
            extensionPoints = extensionPoints,
            services = services,
            extensions = extensions
        )
    }

    private fun getTextContent(parent: Element, tagName: String): String? {
        val nodes = parent.getElementsByTagName(tagName)
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node.parentNode == parent) {
                return node.textContent?.trim()?.takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    private fun parseDepends(root: Element): List<String> {
        val result = mutableListOf<String>()
        val nodes = root.getElementsByTagName("depends")
        for (i in 0 until nodes.length) {
            val text = nodes.item(i).textContent?.trim()
            if (!text.isNullOrEmpty()) {
                result.add(text)
            }
        }
        // Also parse <dependencies><plugin id="..."/></dependencies> format
        val depsNodes = root.getElementsByTagName("dependencies")
        for (i in 0 until depsNodes.length) {
            val depsElem = depsNodes.item(i) as? Element ?: continue
            val pluginNodes = depsElem.getElementsByTagName("plugin")
            for (j in 0 until pluginNodes.length) {
                val pluginElem = pluginNodes.item(j) as? Element ?: continue
                val pluginId = pluginElem.getAttribute("id")?.takeIf { it.isNotEmpty() }
                if (pluginId != null) result.add(pluginId)
            }
        }
        return result
    }

    private fun parseExtensionPoints(root: Element): List<ExtensionPointDecl> {
        val result = mutableListOf<ExtensionPointDecl>()
        val epContainers = root.getElementsByTagName("extensionPoints")
        for (i in 0 until epContainers.length) {
            val container = epContainers.item(i) as? Element ?: continue
            val epNodes = container.getElementsByTagName("extensionPoint")
            for (j in 0 until epNodes.length) {
                val ep = epNodes.item(j) as? Element ?: continue
                val qName = ep.getAttribute("qualifiedName")?.takeIf { it.isNotEmpty() }
                    ?: ep.getAttribute("name")?.takeIf { it.isNotEmpty() }
                    ?: continue
                result.add(
                    ExtensionPointDecl(
                        qualifiedName = qName,
                        interfaceFqn = ep.getAttribute("interface")?.takeIf { it.isNotEmpty() },
                        beanClass = ep.getAttribute("beanClass")?.takeIf { it.isNotEmpty() },
                        area = ep.getAttribute("area")?.takeIf { it.isNotEmpty() }
                    )
                )
            }
        }
        return result
    }

    private fun parseServices(root: Element): List<ServiceDecl> {
        val result = mutableListOf<ServiceDecl>()
        for (level in IntelliJPatterns.SERVICE_LEVELS) {
            val nodes = root.getElementsByTagName(level)
            for (i in 0 until nodes.length) {
                val elem = nodes.item(i) as? Element ?: continue
                val impl = elem.getAttribute("serviceImplementation")?.takeIf { it.isNotEmpty() }
                    ?: elem.getAttribute("implementationClass")?.takeIf { it.isNotEmpty() }
                    ?: continue
                result.add(
                    ServiceDecl(
                        interfaceFqn = elem.getAttribute("serviceInterface")?.takeIf { it.isNotEmpty() },
                        implementationFqn = impl,
                        level = level
                    )
                )
            }
        }
        return result
    }

    private fun parseExtensions(root: Element): List<ExtensionImpl> {
        val result = mutableListOf<ExtensionImpl>()
        val extContainers = root.getElementsByTagName("extensions")
        for (i in 0 until extContainers.length) {
            val container = extContainers.item(i) as? Element ?: continue
            val ns = container.getAttribute("defaultExtensionNs")?.takeIf { it.isNotEmpty() } ?: ""

            val children = container.childNodes
            for (j in 0 until children.length) {
                val child = children.item(j) as? Element ?: continue
                val tagName = child.tagName
                // Skip service declarations (already parsed above)
                if (tagName in IntelliJPatterns.SERVICE_LEVELS) continue

                val epName = if (ns.isNotEmpty()) "$ns.$tagName" else tagName
                val impl = child.getAttribute("implementationClass")?.takeIf { it.isNotEmpty() }
                    ?: child.getAttribute("implementation")?.takeIf { it.isNotEmpty() }
                    ?: child.getAttribute("class")?.takeIf { it.isNotEmpty() }

                if (impl != null) {
                    result.add(ExtensionImpl(extensionPointName = epName, implementationFqn = impl))
                }
            }
        }
        return result
    }
}
