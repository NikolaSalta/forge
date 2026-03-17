package forge.files

import java.nio.file.Files
import java.nio.file.Path

/**
 * Detected file type categories supported by the Forge file processing pipeline.
 */
enum class FileType(val mimeType: String) {
    PDF("application/pdf"),
    DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    CSV("text/csv"),
    JSON("application/json"),
    YAML("application/x-yaml"),
    XML("application/xml"),
    HTML("text/html"),
    MARKDOWN("text/markdown"),
    TEXT("text/plain"),
    PNG("image/png"),
    JPG("image/jpeg"),
    GIF("image/gif"),
    BMP("image/bmp"),
    WEBP("image/webp"),
    SVG("image/svg+xml"),
    CODE("text/x-source-code"),
    UNKNOWN("application/octet-stream");

    val isImage: Boolean
        get() = this in setOf(PNG, JPG, GIF, BMP, WEBP, SVG)

    val isDocument: Boolean
        get() = this in setOf(PDF, DOCX, XLSX, CSV)

    val isText: Boolean
        get() = this in setOf(JSON, YAML, XML, HTML, MARKDOWN, TEXT, CODE, SVG)
}

/**
 * Detects file types by extension mapping and magic-number byte inspection.
 */
object FileTypeDetector {

    // Extension-based lookup table
    private val extensionMap: Map<String, FileType> = mapOf(
        // Documents
        "pdf" to FileType.PDF,
        "docx" to FileType.DOCX,
        "xlsx" to FileType.XLSX,
        "xls" to FileType.XLSX,
        "csv" to FileType.CSV,
        "tsv" to FileType.CSV,

        // Data / Markup
        "json" to FileType.JSON,
        "jsonl" to FileType.JSON,
        "yaml" to FileType.YAML,
        "yml" to FileType.YAML,
        "xml" to FileType.XML,
        "html" to FileType.HTML,
        "htm" to FileType.HTML,
        "xhtml" to FileType.HTML,
        "md" to FileType.MARKDOWN,
        "markdown" to FileType.MARKDOWN,
        "txt" to FileType.TEXT,
        "log" to FileType.TEXT,
        "text" to FileType.TEXT,

        // Images
        "png" to FileType.PNG,
        "jpg" to FileType.JPG,
        "jpeg" to FileType.JPG,
        "gif" to FileType.GIF,
        "bmp" to FileType.BMP,
        "webp" to FileType.WEBP,
        "svg" to FileType.SVG,

        // Code (common extensions)
        "kt" to FileType.CODE,
        "kts" to FileType.CODE,
        "java" to FileType.CODE,
        "scala" to FileType.CODE,
        "groovy" to FileType.CODE,
        "py" to FileType.CODE,
        "pyi" to FileType.CODE,
        "js" to FileType.CODE,
        "jsx" to FileType.CODE,
        "ts" to FileType.CODE,
        "tsx" to FileType.CODE,
        "c" to FileType.CODE,
        "h" to FileType.CODE,
        "cpp" to FileType.CODE,
        "hpp" to FileType.CODE,
        "cc" to FileType.CODE,
        "cxx" to FileType.CODE,
        "cs" to FileType.CODE,
        "go" to FileType.CODE,
        "rs" to FileType.CODE,
        "rb" to FileType.CODE,
        "php" to FileType.CODE,
        "swift" to FileType.CODE,
        "r" to FileType.CODE,
        "m" to FileType.CODE,
        "mm" to FileType.CODE,
        "lua" to FileType.CODE,
        "pl" to FileType.CODE,
        "pm" to FileType.CODE,
        "sh" to FileType.CODE,
        "bash" to FileType.CODE,
        "zsh" to FileType.CODE,
        "fish" to FileType.CODE,
        "bat" to FileType.CODE,
        "cmd" to FileType.CODE,
        "ps1" to FileType.CODE,
        "sql" to FileType.CODE,
        "dart" to FileType.CODE,
        "ex" to FileType.CODE,
        "exs" to FileType.CODE,
        "erl" to FileType.CODE,
        "hs" to FileType.CODE,
        "ml" to FileType.CODE,
        "mli" to FileType.CODE,
        "clj" to FileType.CODE,
        "cljs" to FileType.CODE,
        "vue" to FileType.CODE,
        "svelte" to FileType.CODE,
        "sass" to FileType.CODE,
        "scss" to FileType.CODE,
        "less" to FileType.CODE,
        "css" to FileType.CODE,
        "toml" to FileType.CODE,
        "ini" to FileType.CODE,
        "cfg" to FileType.CODE,
        "conf" to FileType.CODE,
        "properties" to FileType.CODE,
        "gradle" to FileType.CODE,
        "cmake" to FileType.CODE,
        "makefile" to FileType.CODE,
        "dockerfile" to FileType.CODE,
        "tf" to FileType.CODE,
        "hcl" to FileType.CODE,
        "proto" to FileType.CODE,
        "graphql" to FileType.CODE,
        "gql" to FileType.CODE,
        "zig" to FileType.CODE,
        "nim" to FileType.CODE,
        "v" to FileType.CODE,
        "wasm" to FileType.CODE
    )

    /**
     * Detects the [FileType] for the given path. First checks by file extension,
     * then falls back to reading the first 8 bytes for magic number detection.
     */
    fun detectType(path: Path): FileType {
        // 1. Try by extension
        val fileName = path.fileName?.toString()?.lowercase() ?: ""

        // Handle files without extensions like "Makefile", "Dockerfile"
        val baseName = fileName.substringAfterLast('/').lowercase()
        if (baseName in setOf("makefile", "dockerfile", "rakefile", "gemfile", "vagrantfile")) {
            return FileType.CODE
        }

        val extension = fileName.substringAfterLast('.', "").lowercase()
        if (extension.isNotEmpty() && extension != fileName) {
            extensionMap[extension]?.let { return it }
        }

        // 2. Fall back to magic number inspection
        return detectByMagicBytes(path)
    }

    /**
     * Reads the first 8 bytes of the file to identify the type by magic number signatures.
     */
    private fun detectByMagicBytes(path: Path): FileType {
        if (!Files.exists(path) || !Files.isRegularFile(path) || Files.size(path) == 0L) {
            return FileType.UNKNOWN
        }

        val header = ByteArray(8)
        val bytesRead: Int
        try {
            Files.newInputStream(path).use { stream ->
                bytesRead = stream.read(header)
            }
        } catch (_: Exception) {
            return FileType.UNKNOWN
        }

        if (bytesRead < 4) return FileType.UNKNOWN

        // PDF: starts with %PDF
        if (header[0] == 0x25.toByte() && // %
            header[1] == 0x50.toByte() && // P
            header[2] == 0x44.toByte() && // D
            header[3] == 0x46.toByte()    // F
        ) {
            return FileType.PDF
        }

        // PNG: starts with 0x89 P N G
        if (header[0] == 0x89.toByte() &&
            header[1] == 0x50.toByte() && // P
            header[2] == 0x4E.toByte() && // N
            header[3] == 0x47.toByte()    // G
        ) {
            return FileType.PNG
        }

        // JPEG: starts with 0xFF 0xD8
        if (header[0] == 0xFF.toByte() &&
            header[1] == 0xD8.toByte()
        ) {
            return FileType.JPG
        }

        // GIF: starts with GIF8
        if (header[0] == 0x47.toByte() && // G
            header[1] == 0x49.toByte() && // I
            header[2] == 0x46.toByte() && // F
            header[3] == 0x38.toByte()    // 8
        ) {
            return FileType.GIF
        }

        // BMP: starts with BM
        if (header[0] == 0x42.toByte() && // B
            header[1] == 0x4D.toByte()    // M
        ) {
            return FileType.BMP
        }

        // WEBP: starts with RIFF....WEBP (bytes 0-3 = RIFF, bytes 8-11 = WEBP)
        // We only have 8 bytes, check RIFF prefix
        if (header[0] == 0x52.toByte() && // R
            header[1] == 0x49.toByte() && // I
            header[2] == 0x46.toByte() && // F
            header[3] == 0x46.toByte()    // F
        ) {
            // Could be WEBP - would need bytes 8-11 to confirm, treat as WEBP tentatively
            return FileType.WEBP
        }

        // ZIP (DOCX, XLSX are ZIP-based): starts with PK (0x50 0x4B)
        if (header[0] == 0x50.toByte() && // P
            header[1] == 0x4B.toByte()    // K
        ) {
            // Cannot distinguish DOCX from XLSX from plain ZIP without deeper inspection.
            // Return UNKNOWN and let extension-based detection handle it above.
            return FileType.UNKNOWN
        }

        // If it looks like text (all bytes are printable ASCII or whitespace), treat as TEXT
        val textBytes = header.take(bytesRead)
        val looksLikeText = textBytes.all { b ->
            val unsigned = b.toInt() and 0xFF
            unsigned in 0x09..0x0D || unsigned in 0x20..0x7E || unsigned >= 0xC0
        }
        if (looksLikeText) {
            return FileType.TEXT
        }

        return FileType.UNKNOWN
    }
}
