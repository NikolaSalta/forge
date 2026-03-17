package forge.files

import forge.llm.OllamaClient
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

/**
 * Processes image files by reading their bytes, encoding to base64, and sending to
 * the Ollama vision model for analysis and description.
 */
class ImageProcessor(
    private val ollamaClient: OllamaClient,
    private val visionModel: String = "qwen2.5vl:7b"
) : FileExtractor {

    companion object {
        private val SUPPORTED_TYPES = setOf(
            FileType.PNG,
            FileType.JPG,
            FileType.GIF,
            FileType.BMP,
            FileType.WEBP
        )

        private const val ANALYSIS_PROMPT =
            "Analyze this image. If it contains text or code, transcribe all of it exactly. " +
            "If it is a diagram, describe its structure, components, and relationships. " +
            "If it is a UI screenshot, describe the layout, elements, and any visible text."
    }

    override fun supportedTypes(): Set<FileType> = SUPPORTED_TYPES

    override fun extract(file: Path): ExtractionResult {
        val imageBytes = Files.readAllBytes(file)
        val fileType = FileTypeDetector.detectType(file)
        val format = fileType.name.lowercase()

        val base64Data = Base64.getEncoder().encodeToString(imageBytes)

        // Call the vision model to describe the image
        val description = analyzeImage(base64Data)

        val metadata = mutableMapOf<String, String>()
        metadata["format"] = format
        metadata["sizeBytes"] = imageBytes.size.toString()

        // Also try to get image dimensions from the bytes
        try {
            val imageInputStream = javax.imageio.ImageIO.createImageInputStream(file.toFile())
            if (imageInputStream != null) {
                val readers = javax.imageio.ImageIO.getImageReaders(imageInputStream)
                if (readers.hasNext()) {
                    val reader = readers.next()
                    reader.input = imageInputStream
                    metadata["width"] = reader.getWidth(0).toString()
                    metadata["height"] = reader.getHeight(0).toString()
                    reader.dispose()
                }
                imageInputStream.close()
            }
        } catch (_: Exception) {
            // Dimension extraction is best-effort
        }

        return ExtractionResult(
            text = description,
            images = listOf(
                ExtractedImage(
                    data = imageBytes,
                    format = format,
                    description = description
                )
            ),
            metadata = metadata,
            sourceFile = file.fileName.toString(),
            mimeType = fileType.mimeType
        )
    }

    /**
     * Send the base64-encoded image to the vision model for analysis.
     * Uses runBlocking because [FileExtractor.extract] is a synchronous interface,
     * but the OllamaClient requires a coroutine context.
     */
    private fun analyzeImage(base64Data: String): String {
        return try {
            runBlocking {
                ollamaClient.chatWithImages(
                    model = visionModel,
                    prompt = ANALYSIS_PROMPT,
                    images = listOf(base64Data),
                    stream = false
                )
            }
        } catch (e: Exception) {
            "[Image analysis unavailable: ${e.message}]"
        }
    }
}
