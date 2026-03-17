package forge.voice

import forge.VoiceInputConfig
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Metadata about a Whisper GGML model binary.
 */
data class WhisperModelInfo(
    val name: String,
    val sizeBytes: Long,
    val url: String
)

/**
 * Registry of available Whisper GGML models hosted on HuggingFace.
 */
val WHISPER_MODELS: Map<String, WhisperModelInfo> = mapOf(
    "tiny" to WhisperModelInfo(
        name = "tiny",
        sizeBytes = 75_000_000L,
        url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin"
    ),
    "base" to WhisperModelInfo(
        name = "base",
        sizeBytes = 148_000_000L,
        url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin"
    ),
    "small" to WhisperModelInfo(
        name = "small",
        sizeBytes = 488_000_000L,
        url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin"
    ),
    "medium" to WhisperModelInfo(
        name = "medium",
        sizeBytes = 1_533_000_000L,
        url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-medium.bin"
    )
)

/**
 * Resolves the full filesystem path to the GGML model binary for the configured
 * whisper model, expanding a leading `~` to the user's home directory.
 *
 * @param config the voice-input configuration from ForgeConfig
 * @return absolute path to `ggml-{model}.bin` inside the model directory
 */
fun getModelPath(config: VoiceInputConfig): Path {
    val dir = config.whisperModelDir.replaceFirst("~", System.getProperty("user.home"))
    return Paths.get(dir, "ggml-${config.whisperModel}.bin")
}

/**
 * Checks whether the configured whisper model binary already exists on disk.
 *
 * @param config the voice-input configuration from ForgeConfig
 * @return true if the model file is present and is a regular file
 */
fun isModelDownloaded(config: VoiceInputConfig): Boolean {
    val path = getModelPath(config)
    return Files.exists(path) && Files.isRegularFile(path)
}
