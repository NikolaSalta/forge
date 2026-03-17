package forge.core

import forge.ForgeConfig
import forge.llm.ChatMessage
import forge.llm.OllamaClient
import forge.llm.PromptBuilder
import forge.llm.ResponseParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/**
 * Resolved intent from user input, containing the classified task type,
 * the primary target of the request, a confidence score, and a unique task ID.
 */
data class ResolvedIntent(
    val taskType: TaskType,
    val primaryTarget: String,
    val confidence: Float,
    val taskId: String
)

/**
 * Classifies raw user input into a structured [ResolvedIntent] by sending
 * the input through the LLM classification pipeline.
 *
 * The flow:
 * 1. Build a classification prompt via [PromptBuilder.buildIntentClassification].
 * 2. Send it to the classify model via [OllamaClient.chat].
 * 3. Parse the JSON response to extract task_type, primary_target, and confidence.
 * 4. Fall back to [TaskType.REPO_ANALYSIS] if parsing fails.
 */
class IntentResolver(
    private val ollama: OllamaClient,
    private val promptBuilder: PromptBuilder,
    private val config: ForgeConfig
) {
    private val lenientJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Resolves the user's intent from free-form text input.
     *
     * @param userInput the raw text entered by the user
     * @return a [ResolvedIntent] with the classified task type and metadata
     */
    suspend fun resolve(userInput: String): ResolvedIntent {
        val taskId = generateTaskId()

        return try {
            val messages: List<ChatMessage> = promptBuilder.buildIntentClassification(userInput)
            val rawResponse = ollama.chat(config.models.classify, messages)
            parseClassificationResponse(rawResponse, taskId, userInput)
        } catch (e: Exception) {
            // Fallback: if the LLM call or parsing fails entirely, default to REPO_ANALYSIS
            ResolvedIntent(
                taskType = TaskType.REPO_ANALYSIS,
                primaryTarget = extractFallbackTarget(userInput),
                confidence = 0.3f,
                taskId = taskId
            )
        }
    }

    /**
     * Parses the JSON classification response from the LLM.
     * Expected format: {"task_type": "TASK_TYPE_NAME", "primary_target": "...", "confidence": 0.85}
     */
    private fun parseClassificationResponse(
        rawResponse: String,
        taskId: String,
        userInput: String
    ): ResolvedIntent {
        val jsonString = ResponseParser.extractJson(rawResponse)
            ?: return fallbackIntent(taskId, userInput)

        return try {
            val jsonObject = lenientJson.parseToJsonElement(jsonString) as? JsonObject
                ?: return fallbackIntent(taskId, userInput)

            val taskTypeName = jsonObject["task_type"]?.jsonPrimitive?.content
                ?: return fallbackIntent(taskId, userInput)

            val taskType = TaskType.fromString(taskTypeName)
                ?: TaskType.fromString(taskTypeName.replace("-", "_"))
                ?: return fallbackIntent(taskId, userInput)

            val primaryTarget = jsonObject["primary_target"]?.jsonPrimitive?.content
                ?: extractFallbackTarget(userInput)

            val confidence = try {
                jsonObject["confidence"]?.jsonPrimitive?.float ?: 0.7f
            } catch (_: Exception) {
                0.7f
            }

            ResolvedIntent(
                taskType = taskType,
                primaryTarget = primaryTarget,
                confidence = confidence.coerceIn(0.0f, 1.0f),
                taskId = taskId
            )
        } catch (_: Exception) {
            fallbackIntent(taskId, userInput)
        }
    }

    /**
     * Creates a fallback intent when classification fails.
     */
    private fun fallbackIntent(taskId: String, userInput: String): ResolvedIntent {
        return ResolvedIntent(
            taskType = TaskType.REPO_ANALYSIS,
            primaryTarget = extractFallbackTarget(userInput),
            confidence = 0.3f,
            taskId = taskId
        )
    }

    /**
     * Extracts a rough "target" from the user input by taking the first
     * meaningful words, used when the LLM does not provide a primary_target.
     */
    private fun extractFallbackTarget(userInput: String): String {
        val words = userInput.trim().split("\\s+".toRegex())
        return words.take(6).joinToString(" ")
    }

    /**
     * Generates a unique task ID in the format "task-XXXXXXXX" where X is a hex character.
     */
    private fun generateTaskId(): String {
        val uuid = UUID.randomUUID().toString().replace("-", "")
        return "task-${uuid.take(8)}"
    }
}
