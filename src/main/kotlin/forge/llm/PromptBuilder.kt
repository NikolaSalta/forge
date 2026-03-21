package forge.llm

import forge.core.TaskType
import java.util.concurrent.ConcurrentHashMap

/**
 * Builds structured LLM prompts from template files stored in resources/prompts/.
 *
 * Templates use {{variable}} placeholders that are substituted at build time.
 * Supported variables: {{user_input}}, {{evidence}}, {{context_chunks}},
 * {{file_contents}}, {{task_type}}.
 *
 * Templates are loaded lazily from the classpath and cached for the lifetime
 * of this instance.
 */
class PromptBuilder {

    private val templateCache = ConcurrentHashMap<String, String>()

    // ── Public build methods ────────────────────────────────────────────────────

    /**
     * Build messages for intent/task classification from raw user input.
     */
    fun buildIntentClassification(userInput: String): List<ChatMessage> {
        val system = loadTemplate("classify_system")
        val user = loadTemplate("classify_user")
            .substituteAll(mapOf("user_input" to userInput))

        return listOf(
            ChatMessage(role = "system", content = system),
            ChatMessage(role = "user", content = user)
        )
    }

    /**
     * Build messages for executing a task, with retrieved context.
     *
     * @param taskType       The classified task type.
     * @param evidence       Relevant evidence strings gathered during retrieval.
     * @param chunks         Context chunks from the vector store.
     * @param fileContents   Full or partial file contents included for reference.
     */
    fun buildTaskPrompt(
        taskType: TaskType,
        evidence: List<String>,
        chunks: List<String>,
        fileContents: Map<String, String>
    ): List<ChatMessage> {
        // Try task-specific system prompt first, fall back to generic
        val taskTemplateName = taskType.name.lowercase()
        val systemTemplate = tryLoadTemplate("${taskTemplateName}_system")
            ?: tryLoadTemplate("system")
            ?: loadTemplate("task_system")
        val system = systemTemplate.substituteAll(mapOf("task_type" to taskType.displayName))

        // Prioritize and filter evidence for better signal-to-noise
        val prioritizedEvidence = prioritizeEvidence(evidence, taskType)
        val formattedEvidence = if (prioritizedEvidence.isNotEmpty()) {
            prioritizedEvidence.joinToString("\n") { "- $it" }
        } else {
            "(no additional evidence)"
        }

        // Token budget: ~16K tokens for context. deepseek-r1:8b supports 128K tokens
        // but on 18GB M3 with 7GB model loaded, 128K chars causes OOM during inference.
        // 64K chars ≈ 16K tokens — safe for 8B models, large enough for index summaries.
        val maxContextChars = 64_000

        var formattedChunks = if (chunks.isNotEmpty()) {
            chunks.mapIndexed { idx, chunk ->
                "--- Chunk ${idx + 1} ---\n$chunk"
            }.joinToString("\n\n")
        } else {
            "(no context chunks retrieved)"
        }

        // Truncate chunks to fit model context window
        if (formattedChunks.length > maxContextChars) {
            formattedChunks = formattedChunks.take(maxContextChars) +
                "\n\n[... ${chunks.size} chunks truncated to fit model context window ...]"
        }

        var formattedFiles = if (fileContents.isNotEmpty()) {
            fileContents.entries.joinToString("\n\n") { (path, content) ->
                "=== $path ===\n$content"
            }
        } else {
            "(no file contents included)"
        }

        // Truncate files section too
        val remainingBudget = maxOf(8_000, maxContextChars - formattedChunks.length)
        if (formattedFiles.length > remainingBudget) {
            formattedFiles = formattedFiles.take(remainingBudget) +
                "\n\n[... file contents truncated ...]"
        }

        // Try task-specific user prompt, fall back to generic
        val userTemplate = tryLoadTemplate(taskTemplateName)
            ?: loadTemplate("task_user")
        val user = userTemplate.substituteAll(
            mapOf(
                "task_type" to taskType.displayName,
                "evidence" to formattedEvidence,
                "context_chunks" to formattedChunks,
                "file_contents" to formattedFiles
            )
        )

        return listOf(
            ChatMessage(role = "system", content = system),
            ChatMessage(role = "user", content = user)
        )
    }

    /**
     * Build messages for summarizing a block of text.
     */
    fun buildSummary(text: String): List<ChatMessage> {
        val system = loadTemplate("summarize_system")
        val user = loadTemplate("summarize_user")
            .substituteAll(mapOf("user_input" to text))

        return listOf(
            ChatMessage(role = "system", content = system),
            ChatMessage(role = "user", content = user)
        )
    }

    /**
     * Build messages for an IntelliJ Platform-specific task with module context.
     *
     * @param taskType       The classified task type.
     * @param evidence       Relevant evidence strings gathered during retrieval.
     * @param chunks         Context chunks from the vector store.
     * @param fileContents   Full or partial file contents included for reference.
     * @param moduleContext  Module-level context string (dependencies, plugin.xml, etc.).
     * @param focusModule    Optional name of the module to focus on.
     */
    fun buildIntelliJTaskPrompt(
        taskType: TaskType,
        evidence: List<String>,
        chunks: List<String>,
        fileContents: Map<String, String>,
        moduleContext: String,
        focusModule: String?
    ): List<ChatMessage> {
        val system = """You are Forge, an expert AI assistant specialized in the IntelliJ Platform and JetBrains IDE development.

You have deep knowledge of:
- IntelliJ Platform architecture: plugins, extension points, services, actions
- PSI (Program Structure Interface) for code analysis and manipulation
- Plugin SDK and OpenAPI
- Module structure: platform-api/platform-impl patterns
- Extension point mechanism and service architecture
- VFS, Document model, Editor API, Run Configurations

Current task: ${taskType.displayName}
${if (focusModule != null) "Focused module: $focusModule" else "Scope: all modules"}

Rules:
- Base your answers on the evidence and code context provided.
- Follow IntelliJ Platform conventions when suggesting code.
- Reference extension points by their qualified names.
- For plugin development, include plugin.xml configuration."""

        val user = buildString {
            appendLine("Task: ${taskType.displayName}")
            appendLine()
            if (moduleContext.isNotBlank()) {
                appendLine(moduleContext)
                appendLine()
            }
            if (evidence.isNotEmpty()) {
                appendLine("## Evidence")
                for (e in evidence) appendLine("- $e")
                appendLine()
            }
            if (chunks.isNotEmpty()) {
                appendLine("## Relevant Code")
                for (chunk in chunks) {
                    appendLine("```")
                    appendLine(chunk)
                    appendLine("```")
                    appendLine()
                }
            }
            if (fileContents.isNotEmpty()) {
                appendLine("## Attached Files")
                for ((name, content) in fileContents) {
                    appendLine("=== $name ===")
                    appendLine(content.take(5000))
                    appendLine()
                }
            }
        }

        return listOf(
            ChatMessage(role = "system", content = system),
            ChatMessage(role = "user", content = user)
        )
    }

    // ── Evidence prioritization ─────────────────────────────────────────────────

    /**
     * Prioritize evidence items for the prompt. For summary/analysis tasks,
     * structural evidence (monorepo, build, languages) is most valuable.
     * Import-level details (KEY_MODULES, INTEGRATION_POINTS) are capped
     * to avoid drowning the architectural signal.
     */
    private fun prioritizeEvidence(evidence: List<String>, taskType: TaskType): List<String> {
        // Evidence items arrive as "[CATEGORY:key] value"
        // Priority order for analysis: MONOREPO > BUILD > LANGUAGES > CI_CD > DEPS > others
        val highPriority = mutableListOf<String>()   // always included
        val medPriority = mutableListOf<String>()     // included up to limit
        val lowPriority = mutableListOf<String>()     // heavily capped

        for (item in evidence) {
            val upper = item.uppercase()
            when {
                upper.contains("MONOREPO_STRUCTURE") -> highPriority.add(item)
                upper.contains("BUILD_SYSTEM") -> highPriority.add(item)
                upper.contains("LANGUAGES") -> highPriority.add(item)
                upper.contains("CI_CD_SIGNALS") -> highPriority.add(item)
                upper.contains("DEPENDENCIES") -> highPriority.add(item)
                upper.contains("SOURCE_ROOTS") -> medPriority.add(item)
                upper.contains("TEST_ROOTS") -> medPriority.add(item)
                upper.contains("RUNTIME_SHAPE") -> medPriority.add(item)
                upper.contains("CONFIG_FILES") -> medPriority.add(item)
                upper.contains("KEY_MODULES") -> lowPriority.add(item)
                upper.contains("INTEGRATION_POINTS") -> lowPriority.add(item)
                else -> medPriority.add(item)
            }
        }

        // For analysis/overview tasks, cap low-priority items heavily
        val isAnalysisTask = taskType.name in setOf(
            "REPO_ANALYSIS", "PROJECT_OVERVIEW", "ARCHITECTURE_REVIEW",
            "BUILD_AND_RUN_ANALYSIS", "CI_CD_ANALYSIS"
        )

        val result = mutableListOf<String>()
        result.addAll(highPriority)
        result.addAll(medPriority.take(100_000))
        if (isAnalysisTask) {
            result.addAll(lowPriority.take(50_000))
        } else {
            result.addAll(lowPriority.take(100_000))
        }

        return result
    }

    // ── Template loading ────────────────────────────────────────────────────────

    /**
     * Try to load a template, returning null if not found on the classpath.
     */
    private fun tryLoadTemplate(name: String): String? {
        return templateCache.getOrPut("try:$name") {
            val resourcePath = "prompts/$name.txt"
            val stream = Thread.currentThread().contextClassLoader
                ?.getResourceAsStream(resourcePath)
                ?: PromptBuilder::class.java.classLoader?.getResourceAsStream(resourcePath)
            stream?.bufferedReader()?.use { it.readText() } ?: ""
        }.ifEmpty { null }
    }

    /**
     * Load a template by name from resources/prompts/{name}.txt.
     * Falls back to a sensible inline default if the resource is missing.
     */
    private fun loadTemplate(name: String): String {
        return templateCache.getOrPut(name) {
            val resourcePath = "prompts/$name.txt"
            val stream = Thread.currentThread().contextClassLoader
                ?.getResourceAsStream(resourcePath)
                ?: PromptBuilder::class.java.classLoader?.getResourceAsStream(resourcePath)

            if (stream != null) {
                stream.bufferedReader().use { it.readText() }
            } else {
                defaultTemplate(name)
            }
        }
    }

    /**
     * Inline fallback templates used when no resource file is present.
     * These provide reasonable defaults so the system works out of the box
     * before any prompt engineering is done in the resource files.
     */
    private fun defaultTemplate(name: String): String = when (name) {
        "classify_system" -> """
            |You are an intent classifier for a software engineering assistant.
            |Given the user's request, classify it into exactly one task type.
            |Respond with a JSON object: {"task_type": "TASK_TYPE_NAME", "confidence": 0.0-1.0}
            |Valid task types: ${TaskType.entries.joinToString(", ") { it.name }}
        """.trimMargin()

        "classify_user" -> """
            |Classify the following user request into a task type.
            |
            |User request: {{user_input}}
        """.trimMargin()

        "task_system" -> """
            |You are Forge, an expert software engineering assistant.
            |You are performing the task: {{task_type}}.
            |Provide thorough, accurate, and actionable analysis.
            |When generating code, produce complete, working implementations.
            |Use markdown formatting in your response.
        """.trimMargin()

        "task_user" -> """
            |Task: {{task_type}}
            |
            |## Evidence
            |{{evidence}}
            |
            |## Relevant Code Context
            |{{context_chunks}}
            |
            |## File Contents
            |{{file_contents}}
        """.trimMargin()

        "summarize_system" -> """
            |You are a concise summarizer. Produce a clear, structured summary
            |of the provided text. Highlight key points, decisions, and action items.
            |Use markdown formatting.
        """.trimMargin()

        "summarize_user" -> """
            |Summarize the following text:
            |
            |{{user_input}}
        """.trimMargin()

        else -> "{{user_input}}"
    }

    // ── Variable substitution ───────────────────────────────────────────────────

    private fun String.substituteAll(variables: Map<String, String>): String {
        var result = this
        for ((key, value) in variables) {
            result = result.replace("{{$key}}", value)
        }
        return result
    }
}
