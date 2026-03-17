package forge.voice

import forge.VoiceInputConfig
import io.github.givimad.whisperjni.WhisperContext
import io.github.givimad.whisperjni.WhisperFullParams
import io.github.givimad.whisperjni.WhisperJNI
import io.github.givimad.whisperjni.WhisperSamplingStrategy
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Result of a Whisper transcription.
 *
 * @property text       the full transcribed text (all segments concatenated)
 * @property language   the detected or specified language code
 * @property durationMs wall-clock time the transcription took, in milliseconds
 */
data class TranscriptionResult(
    val text: String,
    val language: String,
    val durationMs: Long
)

/**
 * Speech-to-text transcriber backed by the whisper-jni library.
 *
 * Usage:
 * ```
 * val transcriber = WhisperTranscriber(config.voice)
 * transcriber.ensureModel()
 * transcriber.init()
 * val result = transcriber.transcribe(wavBytes)
 * transcriber.close()
 * ```
 *
 * @param voiceConfig voice-input section of [forge.ForgeConfig]
 */
class WhisperTranscriber(private val voiceConfig: VoiceInputConfig) {

    private var whisper: WhisperJNI? = null
    private var context: WhisperContext? = null

    /**
     * Loads the whisper-jni native library, creates a [WhisperJNI] instance,
     * and opens the configured model file.
     *
     * @throws IllegalStateException if the model file does not exist on disk
     */
    fun init() {
        val modelPath = getModelPath(voiceConfig)
        check(Files.exists(modelPath)) {
            "Whisper model not found at $modelPath -- call ensureModel() first"
        }

        WhisperJNI.loadLibrary()
        val jni = WhisperJNI()
        val ctx = jni.init(modelPath)
        whisper = jni
        context = ctx
    }

    /**
     * Transcribes audio data to text.
     *
     * The input may be either a raw WAV file (with a 44-byte RIFF header) or
     * raw PCM 16-bit LE samples. If the first four bytes are "RIFF", the
     * 44-byte header is stripped automatically.
     *
     * @param audioData WAV or raw PCM bytes (signed 16-bit LE, 16 kHz, mono)
     * @param language  BCP-47 language code (e.g. "en"), or "auto" for auto-detection
     * @return [TranscriptionResult] containing the transcribed text
     * @throws IllegalStateException if [init] has not been called
     */
    fun transcribe(audioData: ByteArray, language: String = "auto"): TranscriptionResult {
        val jni = whisper ?: error("WhisperTranscriber not initialised -- call init() first")
        val ctx = context ?: error("WhisperTranscriber not initialised -- call init() first")
        val startTime = System.currentTimeMillis()

        // Strip WAV header if present
        val pcmBytes = if (audioData.size > 44 &&
            audioData[0] == 'R'.code.toByte() &&
            audioData[1] == 'I'.code.toByte() &&
            audioData[2] == 'F'.code.toByte() &&
            audioData[3] == 'F'.code.toByte()
        ) {
            audioData.copyOfRange(44, audioData.size)
        } else {
            audioData
        }

        // Convert signed 16-bit LE PCM bytes to float samples in [-1.0, 1.0]
        val numSamples = pcmBytes.size / 2
        val samples = FloatArray(numSamples)
        for (i in 0 until numSamples) {
            val lo = pcmBytes[i * 2].toInt() and 0xFF
            val hi = pcmBytes[i * 2 + 1].toInt()
            val sample = (hi shl 8) or lo  // signed 16-bit LE
            samples[i] = sample / 32768.0f
        }

        // Configure Whisper parameters
        val params = WhisperFullParams(WhisperSamplingStrategy.GREEDY)
        params.language = if (language == "auto") null else language

        // Run inference via WhisperJNI instance methods
        jni.full(ctx, params, samples, samples.size)

        // Collect all text segments via WhisperJNI instance
        val segmentCount = jni.fullNSegments(ctx)
        val textBuilder = StringBuilder()
        for (i in 0 until segmentCount) {
            textBuilder.append(jni.fullGetSegmentText(ctx, i))
        }

        val elapsed = System.currentTimeMillis() - startTime
        val resolvedLanguage = if (language == "auto") "auto" else language

        return TranscriptionResult(
            text = textBuilder.toString().trim(),
            language = resolvedLanguage,
            durationMs = elapsed
        )
    }

    /**
     * Ensures that the Whisper model binary is available on disk, downloading
     * it from HuggingFace if necessary.
     *
     * @param modelName one of the keys in [WHISPER_MODELS] (default: the configured model)
     * @return true if the model file is available after this call
     */
    fun ensureModel(modelName: String = voiceConfig.whisperModel): Boolean {
        val effectiveConfig = voiceConfig.copy(whisperModel = modelName)
        val modelPath = getModelPath(effectiveConfig)

        if (Files.exists(modelPath) && Files.isRegularFile(modelPath)) {
            return true
        }

        val modelInfo = WHISPER_MODELS[modelName]
        if (modelInfo == null) {
            System.err.println("Unknown whisper model: $modelName")
            System.err.println("Available models: ${WHISPER_MODELS.keys.joinToString()}")
            return false
        }

        // Create directory structure
        val modelDir = modelPath.parent
        if (!Files.exists(modelDir)) {
            Files.createDirectories(modelDir)
        }

        println("Downloading Whisper model '$modelName' (~${modelInfo.sizeBytes / 1_000_000} MB)...")
        println("  URL: ${modelInfo.url}")
        println("  Destination: $modelPath")

        return try {
            val client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build()

            val request = HttpRequest.newBuilder()
                .uri(URI.create(modelInfo.url))
                .GET()
                .build()

            val tmpPath = modelDir.resolve("ggml-${modelName}.bin.part")

            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())

            if (response.statusCode() != 200) {
                System.err.println("Download failed: HTTP ${response.statusCode()}")
                return false
            }

            val contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L)

            response.body().use { inputStream ->
                Files.newOutputStream(tmpPath).use { outputStream ->
                    val buf = ByteArray(65536)
                    var totalRead = 0L
                    var lastProgressPct = -1

                    while (true) {
                        val bytesRead = inputStream.read(buf)
                        if (bytesRead == -1) break
                        outputStream.write(buf, 0, bytesRead)
                        totalRead += bytesRead

                        // Print progress at every 5% increment
                        if (contentLength > 0) {
                            val pct = ((totalRead * 100) / contentLength).toInt()
                            val rounded = (pct / 5) * 5
                            if (rounded > lastProgressPct) {
                                lastProgressPct = rounded
                                val downloadedMb = totalRead / 1_000_000
                                val totalMb = contentLength / 1_000_000
                                println("  Progress: $rounded% ($downloadedMb / $totalMb MB)")
                            }
                        }
                    }
                }
            }

            // Atomically move the completed download into place
            Files.move(tmpPath, modelPath, StandardCopyOption.REPLACE_EXISTING)
            println("Download complete: $modelPath")
            true
        } catch (e: Exception) {
            System.err.println("Failed to download model: ${e.message}")
            e.printStackTrace(System.err)
            false
        }
    }

    /**
     * Releases the Whisper context and JNI resources. Safe to call multiple
     * times or on an uninitialised instance.
     */
    fun close() {
        try {
            context?.close()
        } catch (_: Exception) {
            // ignore
        }
        context = null
        whisper = null
    }
}
