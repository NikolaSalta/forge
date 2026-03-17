package forge.files

import forge.llm.OllamaClient
import java.nio.file.Path
import java.util.Base64

// ── Core interfaces and data classes ────────────────────────────────────────────

/**
 * Extracts structured content from a file of a specific type.
 */
interface FileExtractor {
    /** Extract content from the given file. */
    fun extract(file: Path): ExtractionResult

    /** The set of [FileType]s this extractor can handle. */
    fun supportedTypes(): Set<FileType>
}

/**
 * The result of extracting content from a file.
 */
data class ExtractionResult(
    val text: String,
    val tables: List<String> = emptyList(),
    val images: List<ExtractedImage> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
    val sourceFile: String,
    val mimeType: String
)

/**
 * An image extracted from a document (e.g., embedded in a PDF or DOCX).
 */
data class ExtractedImage(
    val data: ByteArray,
    val format: String,
    var description: String? = null,
    val pageNumber: Int? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ExtractedImage) return false
        return data.contentEquals(other.data) &&
                format == other.format &&
                description == other.description &&
                pageNumber == other.pageNumber
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + format.hashCode()
        result = 31 * result + (description?.hashCode() ?: 0)
        result = 31 * result + (pageNumber ?: 0)
        return result
    }
}

// ── FileProcessor: orchestrator ─────────────────────────────────────────────────

/**
 * Central orchestrator for file processing. Routes files to the correct extractor
 * based on detected type, and optionally describes extracted images using a vision model.
 */
class FileProcessor(
    private val ollamaClient: OllamaClient,
    private val visionModel: String = "qwen2.5vl:7b"
) {
    private val extractors: Map<FileType, FileExtractor>

    init {
        val allExtractors = listOf(
            PdfExtractor(),
            DocxExtractor(),
            XlsxExtractor(),
            CsvExtractor(),
            ImageProcessor(ollamaClient, visionModel),
            TextExtractor()
        )
        val map = mutableMapOf<FileType, FileExtractor>()
        for (extractor in allExtractors) {
            for (type in extractor.supportedTypes()) {
                map[type] = extractor
            }
        }
        extractors = map
    }

    /**
     * Process a single file: detect its type, extract content, and describe any
     * embedded images using the vision model.
     */
    suspend fun process(filePath: Path): ExtractionResult {
        val fileType = FileTypeDetector.detectType(filePath)
        val extractor = extractors[fileType]
            ?: throw UnsupportedFileTypeException(
                "No extractor available for file type $fileType (file: $filePath)"
            )

        val result = extractor.extract(filePath)

        // If the result contains extracted images, describe them via vision model
        if (result.images.isNotEmpty()) {
            for (image in result.images) {
                if (image.description == null) {
                    image.description = describeImage(image.data)
                }
            }
        }

        return result
    }

    /**
     * Combine multiple extraction results into a single formatted string
     * suitable for inclusion in an LLM prompt.
     */
    fun formatForPrompt(results: List<ExtractionResult>): String {
        if (results.isEmpty()) return ""

        val builder = StringBuilder()
        for ((index, result) in results.withIndex()) {
            if (index > 0) {
                builder.appendLine()
                builder.appendLine("---")
                builder.appendLine()
            }

            builder.appendLine("## File: ${result.sourceFile}")
            builder.appendLine("Type: ${result.mimeType}")

            // Metadata
            if (result.metadata.isNotEmpty()) {
                builder.appendLine()
                builder.appendLine("### Metadata")
                for ((key, value) in result.metadata) {
                    builder.appendLine("- **$key**: $value")
                }
            }

            // Main text content
            if (result.text.isNotBlank()) {
                builder.appendLine()
                builder.appendLine("### Content")
                builder.appendLine(result.text.trim())
            }

            // Tables
            if (result.tables.isNotEmpty()) {
                builder.appendLine()
                builder.appendLine("### Tables")
                for ((tableIndex, table) in result.tables.withIndex()) {
                    if (result.tables.size > 1) {
                        builder.appendLine()
                        builder.appendLine("#### Table ${tableIndex + 1}")
                    }
                    builder.appendLine(table)
                }
            }

            // Image descriptions
            if (result.images.isNotEmpty()) {
                val described = result.images.filter { it.description != null }
                if (described.isNotEmpty()) {
                    builder.appendLine()
                    builder.appendLine("### Embedded Images")
                    for ((imgIndex, image) in described.withIndex()) {
                        val pageInfo = if (image.pageNumber != null) " (page ${image.pageNumber})" else ""
                        builder.appendLine("- **Image ${imgIndex + 1}$pageInfo**: ${image.description}")
                    }
                }
            }
        }

        return builder.toString().trimEnd()
    }

    /**
     * Describe an image using the vision model. Encodes image data to base64 and
     * sends it to the Ollama vision model for analysis.
     */
    suspend fun describeImage(imageData: ByteArray): String {
        val base64 = Base64.getEncoder().encodeToString(imageData)
        return try {
            ollamaClient.chatWithImages(
                model = visionModel,
                prompt = "Describe this image concisely. If it contains text or code, transcribe it. " +
                        "If it is a diagram, describe its structure and relationships. " +
                        "If it is a UI screenshot, describe the layout and elements.",
                images = listOf(base64),
                stream = false
            )
        } catch (e: Exception) {
            "[Image description unavailable: ${e.message}]"
        }
    }
}

// ── Exceptions ──────────────────────────────────────────────────────────────────

class UnsupportedFileTypeException(message: String) : RuntimeException(message)
