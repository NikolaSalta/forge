package forge.files

import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFTable
import java.nio.file.Files
import java.nio.file.Path

/**
 * Extracts text, tables, and images from DOCX files using Apache POI.
 */
class DocxExtractor : FileExtractor {

    override fun supportedTypes(): Set<FileType> = setOf(FileType.DOCX)

    override fun extract(file: Path): ExtractionResult {
        val document = Files.newInputStream(file).use { stream ->
            XWPFDocument(stream)
        }

        return document.use { doc ->
            val text = extractParagraphs(doc)
            val tables = extractTables(doc)
            val images = extractImages(doc)
            val metadata = extractMetadata(doc)

            ExtractionResult(
                text = text,
                tables = tables,
                images = images,
                metadata = metadata,
                sourceFile = file.fileName.toString(),
                mimeType = FileType.DOCX.mimeType
            )
        }
    }

    /**
     * Extract all paragraph text from the document, preserving paragraph breaks.
     */
    private fun extractParagraphs(doc: XWPFDocument): String {
        val builder = StringBuilder()
        for (paragraph in doc.paragraphs) {
            val text = paragraph.text
            if (text.isNotBlank()) {
                builder.appendLine(text)
            } else {
                // Preserve empty paragraph as blank line separator
                builder.appendLine()
            }
        }
        return builder.toString().trim()
    }

    /**
     * Extract all tables and format each as a Markdown table string.
     * Returns a list of markdown table strings, one per table in the document.
     */
    private fun extractTables(doc: XWPFDocument): List<String> {
        return doc.tables.mapNotNull { table ->
            formatTableAsMarkdown(table)
        }
    }

    /**
     * Convert an XWPFTable to a Markdown-formatted table string.
     * The first row is treated as the header, followed by a separator row.
     */
    private fun formatTableAsMarkdown(table: XWPFTable): String? {
        val rows = table.rows
        if (rows.isEmpty()) return null

        val builder = StringBuilder()

        // Build all rows as lists of cell text
        val allRows = rows.map { row ->
            row.tableCells.map { cell -> cell.text.trim() }
        }

        if (allRows.isEmpty()) return null

        // Determine maximum column count across all rows
        val maxCols = allRows.maxOf { it.size }

        // Pad rows to uniform column count
        val paddedRows = allRows.map { row ->
            if (row.size < maxCols) {
                row + List(maxCols - row.size) { "" }
            } else {
                row
            }
        }

        // Header row
        val headerRow = paddedRows[0]
        builder.appendLine("| ${headerRow.joinToString(" | ")} |")

        // Separator row
        builder.appendLine("| ${headerRow.joinToString(" | ") { "---" }} |")

        // Data rows
        for (i in 1 until paddedRows.size) {
            builder.appendLine("| ${paddedRows[i].joinToString(" | ")} |")
        }

        return builder.toString().trim()
    }

    /**
     * Extract all embedded images (pictures) from the document.
     * Returns them as [ExtractedImage] with format inferred from content type.
     */
    private fun extractImages(doc: XWPFDocument): List<ExtractedImage> {
        return doc.allPictures.mapNotNull { picture ->
            try {
                val data = picture.data
                if (data != null && data.isNotEmpty()) {
                    val ext = picture.suggestFileExtension() ?: "png"
                    val format = when (ext.lowercase()) {
                        "png" -> "png"
                        "jpeg", "jpg" -> "jpg"
                        "gif" -> "gif"
                        "bmp" -> "bmp"
                        "webp" -> "webp"
                        else -> ext.lowercase()
                    }
                    ExtractedImage(
                        data = data,
                        format = format
                    )
                } else {
                    null
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Extract basic metadata from the DOCX core properties.
     */
    private fun extractMetadata(doc: XWPFDocument): Map<String, String> {
        val meta = mutableMapOf<String, String>()
        meta["paragraphs"] = doc.paragraphs.size.toString()
        meta["tables"] = doc.tables.size.toString()
        meta["images"] = doc.allPictures.size.toString()

        try {
            val properties = doc.properties?.coreProperties
            if (properties != null) {
                properties.title?.takeIf { it.isNotBlank() }?.let { meta["title"] = it }
                properties.creator?.takeIf { it.isNotBlank() }?.let { meta["author"] = it }
                properties.subject?.takeIf { it.isNotBlank() }?.let { meta["subject"] = it }
                properties.description?.takeIf { it.isNotBlank() }?.let { meta["description"] = it }
            }
        } catch (_: Exception) {
            // Core properties may not be available in all documents
        }

        return meta
    }
}
