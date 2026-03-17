package forge.files

import org.jsoup.Jsoup
import java.nio.file.Files
import java.nio.file.Path

/**
 * Extracts text content from text-based file types: JSON, YAML, XML, HTML,
 * Markdown, plain text, SVG, and source code files.
 *
 * For HTML files, uses Jsoup to parse and extract visible text.
 * For all other text types, reads the raw file content as a string.
 */
class TextExtractor : FileExtractor {

    companion object {
        private val SUPPORTED_TYPES = setOf(
            FileType.JSON,
            FileType.YAML,
            FileType.XML,
            FileType.HTML,
            FileType.MARKDOWN,
            FileType.TEXT,
            FileType.SVG,
            FileType.CODE
        )

        /** Maximum file size in bytes to read (10 MB). Larger files are truncated. */
        private const val MAX_READ_BYTES = 10L * 1024 * 1024
    }

    override fun supportedTypes(): Set<FileType> = SUPPORTED_TYPES

    override fun extract(file: Path): ExtractionResult {
        val fileType = FileTypeDetector.detectType(file)

        val text = when (fileType) {
            FileType.HTML -> extractHtml(file)
            else -> extractPlainText(file)
        }

        val metadata = buildMetadata(file, fileType, text)

        return ExtractionResult(
            text = text,
            metadata = metadata,
            sourceFile = file.fileName.toString(),
            mimeType = fileType.mimeType
        )
    }

    /**
     * Parse HTML and extract visible text content using Jsoup.
     * Falls back to raw text reading if Jsoup parsing fails.
     */
    private fun extractHtml(file: Path): String {
        return try {
            val document = Jsoup.parse(file.toFile(), "UTF-8")

            // Remove script and style elements that don't contain visible text
            document.select("script, style, noscript").remove()

            // Extract the text with whitespace normalization
            val bodyText = document.body()?.text() ?: document.text()

            // Also extract the title if present
            val title = document.title()
            if (title.isNotBlank() && !bodyText.startsWith(title)) {
                "$title\n\n$bodyText"
            } else {
                bodyText
            }
        } catch (_: Exception) {
            // Fall back to raw text if Jsoup fails
            extractPlainText(file)
        }
    }

    /**
     * Read file content as plain text. Truncates files larger than [MAX_READ_BYTES].
     */
    private fun extractPlainText(file: Path): String {
        val fileSize = Files.size(file)

        return if (fileSize <= MAX_READ_BYTES) {
            Files.readString(file, Charsets.UTF_8)
        } else {
            // For very large files, read only the first MAX_READ_BYTES
            val bytes = ByteArray(MAX_READ_BYTES.toInt())
            Files.newInputStream(file).use { stream ->
                stream.read(bytes)
            }
            val text = String(bytes, Charsets.UTF_8)
            "$text\n\n[... truncated, file size: ${fileSize / 1024} KB]"
        }
    }

    /**
     * Build metadata about the extracted text file.
     */
    private fun buildMetadata(file: Path, fileType: FileType, text: String): Map<String, String> {
        val meta = mutableMapOf<String, String>()

        meta["type"] = fileType.name.lowercase()

        val fileSize = Files.size(file)
        meta["sizeBytes"] = fileSize.toString()

        val lineCount = text.count { it == '\n' } + 1
        meta["lines"] = lineCount.toString()

        val charCount = text.length
        meta["characters"] = charCount.toString()

        // Detect encoding hints for certain types
        when (fileType) {
            FileType.JSON -> {
                val trimmed = text.trimStart()
                meta["structure"] = when {
                    trimmed.startsWith('[') -> "array"
                    trimmed.startsWith('{') -> "object"
                    else -> "value"
                }
            }
            FileType.XML, FileType.SVG -> {
                // Try to extract root element name
                val rootMatch = Regex("<(\\w[\\w:-]*)").find(text.trimStart().removePrefix("<?xml"))
                rootMatch?.groupValues?.getOrNull(1)?.let { meta["rootElement"] = it }
            }
            FileType.YAML -> {
                // Note if it's a multi-document YAML
                if (text.contains("\n---\n") || text.startsWith("---\n")) {
                    val docCount = Regex("(^|\\n)---").findAll(text).count()
                    if (docCount > 1) {
                        meta["documents"] = docCount.toString()
                    }
                }
            }
            FileType.CODE -> {
                // Add the file extension as language hint
                val ext = file.fileName.toString().substringAfterLast('.', "").lowercase()
                if (ext.isNotEmpty()) {
                    meta["language"] = mapExtensionToLanguage(ext)
                }
            }
            else -> { /* no extra metadata */ }
        }

        return meta
    }

    /**
     * Map common file extensions to human-readable language names.
     */
    private fun mapExtensionToLanguage(ext: String): String = when (ext) {
        "kt", "kts" -> "kotlin"
        "java" -> "java"
        "py", "pyi" -> "python"
        "js", "jsx" -> "javascript"
        "ts", "tsx" -> "typescript"
        "c" -> "c"
        "h" -> "c/c++ header"
        "cpp", "cc", "cxx" -> "c++"
        "hpp" -> "c++ header"
        "cs" -> "c#"
        "go" -> "go"
        "rs" -> "rust"
        "rb" -> "ruby"
        "php" -> "php"
        "swift" -> "swift"
        "scala" -> "scala"
        "groovy" -> "groovy"
        "r" -> "r"
        "m", "mm" -> "objective-c"
        "lua" -> "lua"
        "pl", "pm" -> "perl"
        "sh", "bash", "zsh", "fish" -> "shell"
        "bat", "cmd" -> "batch"
        "ps1" -> "powershell"
        "sql" -> "sql"
        "dart" -> "dart"
        "ex", "exs" -> "elixir"
        "erl" -> "erlang"
        "hs" -> "haskell"
        "ml", "mli" -> "ocaml"
        "clj", "cljs" -> "clojure"
        "vue" -> "vue"
        "svelte" -> "svelte"
        "css" -> "css"
        "scss", "sass" -> "sass/scss"
        "less" -> "less"
        "toml" -> "toml"
        "ini", "cfg", "conf" -> "config"
        "properties" -> "properties"
        "gradle" -> "gradle"
        "cmake" -> "cmake"
        "makefile" -> "makefile"
        "dockerfile" -> "dockerfile"
        "tf", "hcl" -> "terraform/hcl"
        "proto" -> "protobuf"
        "graphql", "gql" -> "graphql"
        "zig" -> "zig"
        "nim" -> "nim"
        "v" -> "v"
        else -> ext
    }
}
