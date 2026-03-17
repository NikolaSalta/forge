package forge.files

import org.apache.pdfbox.Loader
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.text.PDFTextStripper
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * Extracts text, images, and metadata from PDF files using Apache PDFBox 3.x.
 */
class PdfExtractor : FileExtractor {

    override fun supportedTypes(): Set<FileType> = setOf(FileType.PDF)

    override fun extract(file: Path): ExtractionResult {
        val document: PDDocument = Loader.loadPDF(file.toFile())

        return document.use { doc ->
            val text = extractText(doc)
            val images = extractImages(doc)
            val metadata = extractMetadata(doc)

            ExtractionResult(
                text = text,
                images = images,
                metadata = metadata,
                sourceFile = file.fileName.toString(),
                mimeType = FileType.PDF.mimeType
            )
        }
    }

    /**
     * Extract all text content from the PDF using PDFTextStripper.
     */
    private fun extractText(doc: PDDocument): String {
        val stripper = PDFTextStripper()
        stripper.sortByPosition = true
        return stripper.getText(doc).trim()
    }

    /**
     * Extract all embedded images from every page of the PDF.
     * Each image is converted to PNG byte array for consistent handling.
     */
    private fun extractImages(doc: PDDocument): List<ExtractedImage> {
        val images = mutableListOf<ExtractedImage>()

        for ((pageIndex, page) in doc.pages.withIndex()) {
            val resources = page.resources ?: continue
            val xObjectNames = resources.xObjectNames ?: continue

            for (name in xObjectNames) {
                try {
                    val xObject = resources.getXObject(name)
                    if (xObject is PDImageXObject) {
                        val bufferedImage = xObject.image
                        val outputStream = ByteArrayOutputStream()
                        ImageIO.write(bufferedImage, "PNG", outputStream)
                        val imageBytes = outputStream.toByteArray()

                        if (imageBytes.isNotEmpty()) {
                            images.add(
                                ExtractedImage(
                                    data = imageBytes,
                                    format = "png",
                                    pageNumber = pageIndex + 1
                                )
                            )
                        }
                    }
                } catch (_: Exception) {
                    // Skip images that cannot be decoded (e.g., unsupported color spaces)
                }
            }
        }

        return images
    }

    /**
     * Extract document metadata: page count, title, author, subject, creator, and creation date.
     */
    private fun extractMetadata(doc: PDDocument): Map<String, String> {
        val meta = mutableMapOf<String, String>()
        meta["pages"] = doc.numberOfPages.toString()

        val info = doc.documentInformation
        if (info != null) {
            info.title?.takeIf { it.isNotBlank() }?.let { meta["title"] = it }
            info.author?.takeIf { it.isNotBlank() }?.let { meta["author"] = it }
            info.subject?.takeIf { it.isNotBlank() }?.let { meta["subject"] = it }
            info.creator?.takeIf { it.isNotBlank() }?.let { meta["creator"] = it }
            info.creationDate?.let { cal ->
                meta["created"] = cal.time.toString()
            }
        }

        return meta
    }
}
