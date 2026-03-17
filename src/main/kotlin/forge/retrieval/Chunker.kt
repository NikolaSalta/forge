package forge.retrieval

import forge.workspace.Database
import forge.workspace.FileRecord
import java.nio.file.Files
import java.nio.file.Path

/**
 * Information about a single code chunk extracted from a source file.
 */
data class ChunkInfo(
    val content: String,
    val startLine: Int,
    val endLine: Int,
    val chunkType: String,
    val symbolName: String?
)

/**
 * Language-aware code chunker that splits source files at semantic
 * boundaries (function, class, method declarations) when possible,
 * and falls back to line-based windowing with overlap otherwise.
 *
 * Chunk boundaries are detected with per-language regex patterns
 * that match common declaration forms.
 */
class Chunker {

    companion object {
        // ----- Extension -> canonical language key mapping -----
        private val EXTENSION_TO_LANGUAGE: Map<String, String> = mapOf(
            "kt" to "kotlin", "kts" to "kotlin",
            "java" to "java",
            "ts" to "typescript", "tsx" to "typescript",
            "js" to "javascript", "jsx" to "javascript",
            "py" to "python",
            "go" to "go",
            "rs" to "rust",
            "cs" to "csharp",
            "cpp" to "cpp", "cc" to "cpp", "cxx" to "cpp",
            "c" to "c",
            "h" to "c-header", "hpp" to "cpp-header",
            "scala" to "scala",
            "rb" to "ruby",
            "php" to "php",
            "swift" to "swift"
        )

        // ----- Per-language boundary patterns -----
        // Each pattern matches the beginning of a function, class, or other
        // top-level declaration so the chunker can split the file at those lines.

        private val BOUNDARY_PATTERNS: Map<String, Regex> = mapOf(
            "kotlin" to Regex(
                """(?:fun|class|interface|object|enum\s+class|data\s+class|sealed\s+class)\s+\w+"""
            ),
            "java" to Regex(
                """(?:public|private|protected|static|\s)*(?:class|interface|enum|void|abstract)\s+\w+"""
            ),
            "python" to Regex(
                """(?:def|class|async\s+def)\s+\w+"""
            ),
            "typescript" to Regex(
                """(?:function|class|const|let|var|export|async\s+function)\s+\w+|(?:=>)"""
            ),
            "javascript" to Regex(
                """(?:function|class|const|let|var|export|async\s+function)\s+\w+|(?:=>)"""
            ),
            "go" to Regex(
                """func\s+(?:\([^)]+\)\s+)?\w+"""
            ),
            "rust" to Regex(
                """(?:fn|struct|enum|impl|trait|pub\s+fn|pub\s+struct)\s+\w+"""
            ),
            "csharp" to Regex(
                """(?:public|private|protected|internal|static|class|interface|struct|void)\s+\w+"""
            ),
            "c" to Regex(
                """(?:void|int|char|bool|auto|class|struct|template)\s+\w+\s*[({]"""
            ),
            "cpp" to Regex(
                """(?:void|int|char|bool|auto|class|struct|template)\s+\w+\s*[({]"""
            ),
            "c-header" to Regex(
                """(?:void|int|char|bool|auto|class|struct|template)\s+\w+\s*[({]"""
            ),
            "cpp-header" to Regex(
                """(?:void|int|char|bool|auto|class|struct|template)\s+\w+\s*[({]"""
            ),
            "scala" to Regex(
                """(?:def|class|object|trait|case\s+class)\s+\w+"""
            ),
            "ruby" to Regex(
                """(?:def|class|module)\s+\w+"""
            ),
            "php" to Regex(
                """(?:function|class|interface|trait)\s+\w+"""
            ),
            "swift" to Regex(
                """(?:func|class|struct|enum|protocol|extension)\s+\w+"""
            )
        )

        // Pattern to extract a symbol name from a boundary line.
        // Captures the last identifier-like word after the keyword.
        private val SYMBOL_NAME_PATTERN = Regex(
            """(?:fun|class|interface|object|enum|data|sealed|struct|impl|trait|def|func|function|module|protocol|extension|const|let|var|export)\s+(\w+)"""
        )
    }

    /**
     * Detects the canonical language key for a filename based on its extension.
     */
    fun detectLanguage(fileName: String): String {
        val dotIndex = fileName.lastIndexOf('.')
        if (dotIndex < 0 || dotIndex >= fileName.length - 1) return "unknown"
        val ext = fileName.substring(dotIndex + 1).lowercase()
        return EXTENSION_TO_LANGUAGE[ext] ?: "unknown"
    }

    // -----------------------------------------------------------------------
    //  Single-file chunking
    // -----------------------------------------------------------------------

    /**
     * Splits [content] into semantic chunks using the boundary patterns
     * for [language]. Returns at most [maxChunks] chunks, each no longer
     * than [maxChunkLines] lines. When no semantic boundaries are found,
     * falls back to fixed-window chunking with [overlapLines] overlap.
     */
    fun chunkFile(
        fileId: Int,
        content: String,
        language: String,
        maxChunkLines: Int = 80,
        overlapLines: Int = 10,
        maxChunks: Int = 50
    ): List<ChunkInfo> {
        if (content.isBlank()) return emptyList()

        val lines = content.lines()
        if (lines.size <= maxChunkLines) {
            // Entire file fits in a single chunk.
            val symbolName = extractFirstSymbol(lines)
            return listOf(
                ChunkInfo(
                    content = content,
                    startLine = 1,
                    endLine = lines.size,
                    chunkType = "file",
                    symbolName = symbolName
                )
            )
        }

        val boundaryPattern = BOUNDARY_PATTERNS[language]
        val boundaryIndices = if (boundaryPattern != null) {
            findBoundaryLines(lines, boundaryPattern)
        } else {
            emptyList()
        }

        val chunks = if (boundaryIndices.size >= 2) {
            chunkByBoundaries(lines, boundaryIndices, maxChunkLines, overlapLines)
        } else {
            chunkByWindow(lines, maxChunkLines, overlapLines)
        }

        return chunks.take(maxChunks)
    }

    // -----------------------------------------------------------------------
    //  Batch operation -- reads files from DB, chunks, and saves
    // -----------------------------------------------------------------------

    /**
     * Reads every file from [db], loads its content from disk relative to
     * [repoPath], chunks it, and persists the resulting chunks back to [db].
     */
    fun chunkAll(
        db: Database,
        repoPath: Path,
        maxChunkLines: Int = 80,
        overlapLines: Int = 10,
        maxChunksPerFile: Int = 50
    ) {
        val allFiles = db.getAllFiles()
        val resolvedRoot = repoPath.toAbsolutePath().normalize()

        for (file in allFiles) {
            // Only chunk source-code and test files.
            if (file.category != "source" && file.category != "test") continue

            val filePath = resolvedRoot.resolve(file.relativePath)
            if (!Files.isRegularFile(filePath)) continue

            val content = try {
                Files.readString(filePath)
            } catch (_: Exception) {
                continue
            }
            if (content.isBlank()) continue

            val language = detectLanguageFromRecord(file)
            val chunks = chunkFile(
                fileId = file.id,
                content = content,
                language = language,
                maxChunkLines = maxChunkLines,
                overlapLines = overlapLines,
                maxChunks = maxChunksPerFile
            )

            for (chunk in chunks) {
                db.insertChunk(
                    fileId = file.id,
                    content = chunk.content,
                    startLine = chunk.startLine,
                    endLine = chunk.endLine,
                    chunkType = chunk.chunkType,
                    symbolName = chunk.symbolName,
                    language = language
                )
            }
        }
    }

    // -----------------------------------------------------------------------
    //  Boundary-based chunking
    // -----------------------------------------------------------------------

    /**
     * Scans [lines] with [pattern] and returns the 0-based indices of
     * lines that match (i.e. likely declaration boundaries).
     */
    private fun findBoundaryLines(lines: List<String>, pattern: Regex): List<Int> {
        val result = mutableListOf<Int>()
        for ((index, line) in lines.withIndex()) {
            if (pattern.containsMatchIn(line)) {
                result.add(index)
            }
        }
        return result
    }

    /**
     * Splits the file at boundary lines. Each chunk starts at a boundary
     * and extends to just before the next boundary (or end of file).
     * Chunks exceeding [maxChunkLines] are further split with overlap.
     */
    private fun chunkByBoundaries(
        lines: List<String>,
        boundaries: List<Int>,
        maxChunkLines: Int,
        overlapLines: Int
    ): List<ChunkInfo> {
        val chunks = mutableListOf<ChunkInfo>()

        // If the file starts before the first boundary, emit a preamble chunk.
        if (boundaries.first() > 0) {
            val preambleLines = lines.subList(0, boundaries.first())
            if (preambleLines.isNotEmpty()) {
                chunks.addAll(
                    splitRegionIntoChunks(
                        lines, 0, boundaries.first() - 1,
                        maxChunkLines, overlapLines, "block", null
                    )
                )
            }
        }

        for (i in boundaries.indices) {
            val start = boundaries[i]
            val end = if (i + 1 < boundaries.size) boundaries[i + 1] - 1 else lines.size - 1
            val symbolName = extractSymbolFromLine(lines[start])
            val chunkType = inferChunkType(lines[start])

            chunks.addAll(
                splitRegionIntoChunks(lines, start, end, maxChunkLines, overlapLines, chunkType, symbolName)
            )
        }

        return chunks
    }

    /**
     * Splits a region [startIdx]..[endIdx] (0-based, inclusive) into chunks
     * of at most [maxChunkLines]. If the region already fits, a single chunk
     * is returned. Otherwise fixed-window splitting with [overlapLines] is used.
     */
    private fun splitRegionIntoChunks(
        lines: List<String>,
        startIdx: Int,
        endIdx: Int,
        maxChunkLines: Int,
        overlapLines: Int,
        chunkType: String,
        symbolName: String?
    ): List<ChunkInfo> {
        val regionSize = endIdx - startIdx + 1
        if (regionSize <= 0) return emptyList()

        if (regionSize <= maxChunkLines) {
            val content = lines.subList(startIdx, endIdx + 1).joinToString("\n")
            return listOf(
                ChunkInfo(
                    content = content,
                    startLine = startIdx + 1,
                    endLine = endIdx + 1,
                    chunkType = chunkType,
                    symbolName = symbolName
                )
            )
        }

        // Break large region into windows.
        val result = mutableListOf<ChunkInfo>()
        var pos = startIdx
        var isFirst = true
        while (pos <= endIdx) {
            val chunkEnd = minOf(pos + maxChunkLines - 1, endIdx)
            val content = lines.subList(pos, chunkEnd + 1).joinToString("\n")
            result.add(
                ChunkInfo(
                    content = content,
                    startLine = pos + 1,
                    endLine = chunkEnd + 1,
                    chunkType = if (isFirst) chunkType else "block",
                    symbolName = if (isFirst) symbolName else null
                )
            )
            isFirst = false
            pos = chunkEnd + 1 - overlapLines
            if (pos <= (result.last().endLine - 1)) {
                pos = chunkEnd + 1
            }
        }
        return result
    }

    // -----------------------------------------------------------------------
    //  Fallback: fixed-window chunking
    // -----------------------------------------------------------------------

    /**
     * Simple line-based windowing used when no semantic boundaries were found.
     */
    private fun chunkByWindow(
        lines: List<String>,
        maxChunkLines: Int,
        overlapLines: Int
    ): List<ChunkInfo> {
        val chunks = mutableListOf<ChunkInfo>()
        var pos = 0
        while (pos < lines.size) {
            val chunkEnd = minOf(pos + maxChunkLines - 1, lines.size - 1)
            val content = lines.subList(pos, chunkEnd + 1).joinToString("\n")
            val symbolName = extractFirstSymbol(lines.subList(pos, chunkEnd + 1))
            chunks.add(
                ChunkInfo(
                    content = content,
                    startLine = pos + 1,
                    endLine = chunkEnd + 1,
                    chunkType = "block",
                    symbolName = symbolName
                )
            )
            val nextPos = chunkEnd + 1 - overlapLines
            pos = if (nextPos <= pos) chunkEnd + 1 else nextPos
        }
        return chunks
    }

    // -----------------------------------------------------------------------
    //  Symbol extraction helpers
    // -----------------------------------------------------------------------

    private fun extractSymbolFromLine(line: String): String? {
        val match = SYMBOL_NAME_PATTERN.find(line) ?: return null
        return match.groupValues.getOrNull(1)
    }

    private fun extractFirstSymbol(lines: List<String>): String? {
        for (line in lines) {
            val sym = extractSymbolFromLine(line)
            if (sym != null) return sym
        }
        return null
    }

    /**
     * Infers chunk type from a boundary line.
     */
    private fun inferChunkType(line: String): String {
        val trimmed = line.trimStart()
        return when {
            trimmed.contains("class ") || trimmed.contains("interface ") ||
                trimmed.contains("object ") || trimmed.contains("enum ") ||
                trimmed.contains("struct ") || trimmed.contains("trait ") ||
                trimmed.contains("module ") || trimmed.contains("protocol ") ||
                trimmed.contains("extension ") -> "class"

            trimmed.contains("fun ") || trimmed.contains("func ") ||
                trimmed.contains("function ") || trimmed.contains("def ") ||
                trimmed.contains("fn ") -> "function"

            trimmed.contains("impl ") -> "impl"

            else -> "block"
        }
    }

    /**
     * Resolves the language of a [FileRecord] from its stored language field
     * or, as a fallback, from the file extension.
     */
    private fun detectLanguageFromRecord(file: FileRecord): String {
        val lang = file.language
        if (lang != null && lang != "unknown" && lang.isNotBlank()) return lang
        val dotIndex = file.relativePath.lastIndexOf('.')
        if (dotIndex < 0) return "unknown"
        val ext = file.relativePath.substring(dotIndex + 1).lowercase()
        return EXTENSION_TO_LANGUAGE[ext] ?: "unknown"
    }
}
