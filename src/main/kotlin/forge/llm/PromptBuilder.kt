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
        val system = loadTemplate("task_system")
            .substituteAll(mapOf("task_type" to taskType.displayName))

        val formattedEvidence = if (evidence.isNotEmpty()) {
            evidence.joinToString("\n") { "- $it" }
        } else {
            "(no additional evidence)"
        }

        val formattedChunks = if (chunks.isNotEmpty()) {
            chunks.mapIndexed { idx, chunk ->
                "--- Chunk ${idx + 1} ---\n$chunk"
            }.joinToString("\n\n")
        } else {
            "(no context chunks retrieved)"
        }

        val formattedFiles = if (fileContents.isNotEmpty()) {
            fileContents.entries.joinToString("\n\n") { (path, content) ->
                "=== $path ===\n$content"
            }
        } else {
            "(no file contents included)"
        }

        val user = loadTemplate("task_user")
            .substituteAll(
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

    // ── Template loading ────────────────────────────────────────────────────────

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
