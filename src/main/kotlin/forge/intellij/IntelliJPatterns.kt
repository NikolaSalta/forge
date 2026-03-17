package forge.intellij

/**
 * Constants and regex patterns for detecting IntelliJ Platform code patterns.
 */
object IntelliJPatterns {

    /** Known IntelliJ Platform module type markers */
    val PLATFORM_API_PATTERN = Regex("""platform/[\w-]+-api""")
    val PLATFORM_IMPL_PATTERN = Regex("""platform/[\w-]+-impl""")
    val PLUGIN_PATTERN = Regex("""plugins/[\w-]+""")

    /** Source file extensions relevant to IntelliJ development */
    val SOURCE_EXTENSIONS = setOf(
        "java", "kt", "kts", "groovy", "xml", "properties"
    )

    /** Module marker files */
    val MODULE_MARKERS = listOf(
        "META-INF/plugin.xml",
        "src/META-INF/plugin.xml",
        "resources/META-INF/plugin.xml",
        "src/main/resources/META-INF/plugin.xml"
    )

    /** Patterns for IntelliJ API classes */
    val ACTION_PATTERN = Regex("""class\s+\w+\s*.*:\s*.*AnAction""")
    val INTENTION_PATTERN = Regex("""class\s+\w+\s*.*:\s*.*IntentionAction""")
    val INSPECTION_PATTERN = Regex("""class\s+\w+\s*.*:\s*.*(?:Local|Global)?InspectionTool""")
    val PSI_ELEMENT_PATTERN = Regex("""class\s+\w+\s*.*:\s*.*PsiElement""")
    val PSI_REFERENCE_PATTERN = Regex("""class\s+\w+\s*.*:\s*.*PsiReference""")
    val PSI_VISITOR_PATTERN = Regex("""class\s+\w+\s*.*:\s*.*PsiElementVisitor""")
    val FILE_TYPE_PATTERN = Regex("""class\s+\w+\s*.*:\s*.*(?:FileType|LanguageFileType)""")
    val PARSER_PATTERN = Regex("""class\s+\w+\s*.*:\s*.*ParserDefinition""")
    val HIGHLIGHTER_PATTERN = Regex("""class\s+\w+\s*.*:\s*.*SyntaxHighlighter""")
    val COMPLETION_PATTERN = Regex("""class\s+\w+\s*.*:\s*.*CompletionContributor""")
    val TOOL_WINDOW_PATTERN = Regex("""class\s+\w+\s*.*:\s*.*ToolWindowFactory""")
    val CONFIGURABLE_PATTERN = Regex("""class\s+\w+\s*.*:\s*.*(?:Configurable|SearchableConfigurable)""")

    /** Module type classification by path */
    fun classifyModuleType(relativePath: String): ModuleType {
        return when {
            relativePath.matches(Regex("""platform/[\w.-]+-api(/.*)?""")) -> ModuleType.PLATFORM_API
            relativePath.matches(Regex("""platform/[\w.-]+-impl(/.*)?""")) -> ModuleType.PLATFORM_IMPL
            relativePath.startsWith("platform/") -> ModuleType.PLATFORM_API
            relativePath.startsWith("plugins/") -> ModuleType.PLUGIN
            relativePath.startsWith("lib/") || relativePath.startsWith("libraries/") -> ModuleType.LIBRARY
            relativePath.contains("test", ignoreCase = true) -> ModuleType.TEST
            relativePath.startsWith("java/") -> ModuleType.PLUGIN
            relativePath.startsWith("python/") -> ModuleType.PLUGIN
            relativePath.startsWith("json/") -> ModuleType.PLUGIN
            relativePath.startsWith("xml/") -> ModuleType.PLUGIN
            else -> ModuleType.COMMUNITY
        }
    }

    /** Known IntelliJ extension point areas */
    val EP_AREAS = setOf("IDEA_APPLICATION", "IDEA_PROJECT", "IDEA_MODULE")

    /** Service level markers in plugin.xml */
    val SERVICE_LEVELS = setOf("applicationService", "projectService", "moduleService")

    /** Directories to always skip in IntelliJ repo */
    val INTELLIJ_SKIP_DIRS = setOf(
        ".git", ".idea", "out", "build", "dist",
        "node_modules", "__pycache__", ".gradle",
        "testData", "test-data", "testResources"
    )
}

/**
 * Type classification for IntelliJ modules.
 */
enum class ModuleType(val displayName: String) {
    PLATFORM_API("Platform API"),
    PLATFORM_IMPL("Platform Impl"),
    PLUGIN("Plugin"),
    LIBRARY("Library"),
    TEST("Test"),
    COMMUNITY("Community")
}
