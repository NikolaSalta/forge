package forge.llm

import forge.ForgeConfig
import forge.OllamaConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ConnectException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

// ── Data classes ────────────────────────────────────────────────────────────────

@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
    val images: List<String>? = null
)

@Serializable
data class ModelInfo(
    val name: String,
    val size: Long = 0,
    @SerialName("parameter_size") val parameterSize: String = "",
    val family: String = "",
    @SerialName("quantization_level") val quantizationLevel: String = ""
)

@Serializable
data class ModelDetails(
    val modelfile: String = "",
    val parameters: String = "",
    val template: String = "",
    val license: String = "",
    val system: String = ""
)

@Serializable
data class ChatResponse(
    val model: String = "",
    val message: ChatResponseMessage = ChatResponseMessage(),
    val done: Boolean = false,
    @SerialName("total_duration") val totalDuration: Long = 0,
    @SerialName("prompt_eval_count") val promptEvalCount: Int = 0,
    @SerialName("eval_count") val evalCount: Int = 0
)

@Serializable
data class ChatResponseMessage(
    val role: String = "assistant",
    val content: String = ""
)

@Serializable
data class GenerateResponse(
    val model: String = "",
    val response: String = "",
    val done: Boolean = false,
    @SerialName("total_duration") val totalDuration: Long = 0,
    @SerialName("prompt_eval_count") val promptEvalCount: Int = 0,
    @SerialName("eval_count") val evalCount: Int = 0
)

@Serializable
data class EmbeddingResponse(
    val embedding: List<Float> = emptyList()
)

// ── Request bodies (internal) ───────────────────────────────────────────────────

@Serializable
private data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = false
)

@Serializable
private data class GenerateRequest(
    val model: String,
    val prompt: String,
    val stream: Boolean = false
)

@Serializable
private data class EmbeddingRequest(
    val model: String,
    val prompt: String
)

@Serializable
private data class ShowModelRequest(
    val name: String
)

// ── OllamaClient ────────────────────────────────────────────────────────────────

class OllamaClient(
    private val config: OllamaConfig = OllamaConfig()
) {
    private val baseUrl: String = config.host.trimEnd('/')
    private val timeout: Duration = Duration.ofSeconds(config.timeoutSeconds.toLong())
    private val maxRetries: Int = config.retryCount

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    constructor(forgeConfig: ForgeConfig) : this(forgeConfig.ollama)

    // ── Chat (non-streaming) ────────────────────────────────────────────────────

    suspend fun chat(
        model: String,
        messages: List<ChatMessage>,
        stream: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        val requestBody = json.encodeToString(ChatRequest(model, messages, stream = false))
        val responseText = postWithRetry("$baseUrl/api/chat", requestBody)
        val chatResponse = json.decodeFromString<ChatResponse>(responseText)
        chatResponse.message.content
    }

    // ── Chat (streaming) ────────────────────────────────────────────────────────

    fun chatStream(
        model: String,
        messages: List<ChatMessage>
    ): Flow<String> = flow {
        val requestBody = json.encodeToString(ChatRequest(model, messages, stream = true))
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/api/chat"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .timeout(timeout)
            .build()

        val response = executeWithRetry {
            httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
        }

        if (response.statusCode() !in 200..299) {
            val errorBody = response.body().bufferedReader().use { it.readText() }
            throw OllamaException("Chat stream failed (HTTP ${response.statusCode()}): $errorBody")
        }

        val reader = BufferedReader(InputStreamReader(response.body()))
        reader.use {
            var line = reader.readLine()
            while (line != null) {
                if (line.isNotBlank()) {
                    val token = extractStreamToken(line)
                    if (token.isNotEmpty()) {
                        emit(token)
                    }
                }
                line = reader.readLine()
            }
        }
    }.flowOn(Dispatchers.IO)

    // ── Chat with images (vision models) ────────────────────────────────────────

    suspend fun chatWithImages(
        model: String,
        prompt: String,
        images: List<String>,
        stream: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        val message = ChatMessage(role = "user", content = prompt, images = images)
        val requestBody = json.encodeToString(ChatRequest(model, listOf(message), stream = false))
        val responseText = postWithRetry("$baseUrl/api/chat", requestBody)
        val chatResponse = json.decodeFromString<ChatResponse>(responseText)
        chatResponse.message.content
    }

    // ── Generate ────────────────────────────────────────────────────────────────

    suspend fun generate(
        model: String,
        prompt: String,
        stream: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        val requestBody = json.encodeToString(GenerateRequest(model, prompt, stream = false))
        val responseText = postWithRetry("$baseUrl/api/generate", requestBody)
        val genResponse = json.decodeFromString<GenerateResponse>(responseText)
        genResponse.response
    }

    // ── Embeddings ──────────────────────────────────────────────────────────────

    suspend fun embed(model: String, text: String): FloatArray = withContext(Dispatchers.IO) {
        val requestBody = json.encodeToString(EmbeddingRequest(model, text))
        val responseText = postWithRetry("$baseUrl/api/embeddings", requestBody)
        val embeddingResponse = json.decodeFromString<EmbeddingResponse>(responseText)
        embeddingResponse.embedding.toFloatArray()
    }

    // ── List models ─────────────────────────────────────────────────────────────

    suspend fun listModels(): List<ModelInfo> = withContext(Dispatchers.IO) {
        val responseText = getWithRetry("$baseUrl/api/tags")
        val root = json.parseToJsonElement(responseText).jsonObject
        val modelsArray = root["models"]?.jsonArray ?: return@withContext emptyList()

        modelsArray.map { element ->
            val obj = element.jsonObject
            val details = obj["details"]?.jsonObject
            ModelInfo(
                name = obj["name"]?.jsonPrimitive?.content ?: "",
                size = obj["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                parameterSize = details?.get("parameter_size")?.jsonPrimitive?.content ?: "",
                family = details?.get("family")?.jsonPrimitive?.content ?: "",
                quantizationLevel = details?.get("quantization_level")?.jsonPrimitive?.content ?: ""
            )
        }
    }

    // ── Show model ──────────────────────────────────────────────────────────────

    suspend fun showModel(name: String): ModelDetails = withContext(Dispatchers.IO) {
        val requestBody = json.encodeToString(ShowModelRequest(name))
        val responseText = postWithRetry("$baseUrl/api/show", requestBody)
        val root = json.parseToJsonElement(responseText).jsonObject
        ModelDetails(
            modelfile = root["modelfile"]?.jsonPrimitive?.content ?: "",
            parameters = root["parameters"]?.jsonPrimitive?.content ?: "",
            template = root["template"]?.jsonPrimitive?.content ?: "",
            license = root["license"]?.jsonPrimitive?.content ?: "",
            system = root["system"]?.jsonPrimitive?.content ?: ""
        )
    }

    // ── Availability check ──────────────────────────────────────────────────────

    suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl))
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            response.statusCode() == 200
        } catch (_: Exception) {
            false
        }
    }

    // ── Internal helpers ────────────────────────────────────────────────────────

    private fun extractStreamToken(line: String): String {
        return try {
            val obj = json.parseToJsonElement(line).jsonObject
            obj["message"]?.jsonObject?.get("content")?.jsonPrimitive?.content ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun postWithRetry(url: String, body: String): String {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .timeout(timeout)
            .build()

        val response = executeWithRetry {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        }

        if (response.statusCode() !in 200..299) {
            throw OllamaException("Request to $url failed (HTTP ${response.statusCode()}): ${response.body()}")
        }
        return response.body()
    }

    private fun getWithRetry(url: String): String {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .GET()
            .timeout(timeout)
            .build()

        val response = executeWithRetry {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        }

        if (response.statusCode() !in 200..299) {
            throw OllamaException("Request to $url failed (HTTP ${response.statusCode()}): ${response.body()}")
        }
        return response.body()
    }

    private fun <T> executeWithRetry(action: () -> T): T {
        var lastException: Exception? = null
        for (attempt in 0..maxRetries) {
            try {
                return action()
            } catch (e: ConnectException) {
                lastException = e
                if (attempt < maxRetries) {
                    Thread.sleep(500L * (attempt + 1))
                }
            } catch (e: Exception) {
                // For non-connection errors, do not retry
                throw OllamaException("Ollama request failed: ${e.message}", e)
            }
        }
        throw OllamaException(
            "Failed to connect to Ollama at $baseUrl after ${maxRetries + 1} attempts: ${lastException?.message}",
            lastException
        )
    }
}

// ── Exception ───────────────────────────────────────────────────────────────────

class OllamaException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
