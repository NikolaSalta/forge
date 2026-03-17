package forge.files

import java.io.BufferedReader
import java.nio.file.Files
import java.nio.file.Path

/**
 * Extracts tabular data from CSV/TSV files and formats it as a Markdown table.
 * Automatically detects the delimiter by analyzing the first few lines.
 */
class CsvExtractor : FileExtractor {

    override fun supportedTypes(): Set<FileType> = setOf(FileType.CSV)

    override fun extract(file: Path): ExtractionResult {
        val lines = Files.readAllLines(file, Charsets.UTF_8)
            .filter { it.isNotBlank() }

        if (lines.isEmpty()) {
            return ExtractionResult(
                text = "",
                tables = emptyList(),
                sourceFile = file.fileName.toString(),
                mimeType = FileType.CSV.mimeType
            )
        }

        val delimiter = detectDelimiter(lines)
        val parsedRows = lines.map { line -> parseCsvLine(line, delimiter) }

        // Determine max column count
        val maxCols = parsedRows.maxOf { it.size }

        // Pad rows to uniform column count
        val paddedRows = parsedRows.map { row ->
            if (row.size < maxCols) {
                row + List(maxCols - row.size) { "" }
            } else {
                row
            }
        }

        // Build markdown table
        val tableBuilder = StringBuilder()

        if (paddedRows.isNotEmpty()) {
            // Header
            val headerRow = paddedRows[0]
            tableBuilder.appendLine("| ${headerRow.joinToString(" | ")} |")
            tableBuilder.appendLine("| ${headerRow.joinToString(" | ") { "---" }} |")

            // Data rows
            for (i in 1 until paddedRows.size) {
                tableBuilder.appendLine("| ${paddedRows[i].joinToString(" | ")} |")
            }
        }

        val table = tableBuilder.toString().trim()
        val metadata = mutableMapOf<String, String>()
        metadata["rows"] = lines.size.toString()
        metadata["columns"] = maxCols.toString()
        metadata["delimiter"] = when (delimiter) {
            ',' -> "comma"
            ';' -> "semicolon"
            '\t' -> "tab"
            else -> delimiter.toString()
        }

        return ExtractionResult(
            text = "",
            tables = if (table.isNotBlank()) listOf(table) else emptyList(),
            metadata = metadata,
            sourceFile = file.fileName.toString(),
            mimeType = FileType.CSV.mimeType
        )
    }

    /**
     * Detect the delimiter by counting occurrences of common delimiters in the first
     * few lines and picking the one with the most consistent count across lines.
     */
    private fun detectDelimiter(lines: List<String>): Char {
        val candidates = charArrayOf(',', ';', '\t')
        val sampleLines = lines.take(minOf(10, lines.size))

        if (sampleLines.size <= 1) {
            // With only one line, pick the delimiter with the most occurrences
            val line = sampleLines[0]
            return candidates.maxByOrNull { delimiter ->
                countDelimiterInLine(line, delimiter)
            } ?: ','
        }

        // For each candidate delimiter, compute how consistent the column count is
        // across sample lines. A good delimiter yields the same count on every line.
        var bestDelimiter = ','
        var bestScore = -1

        for (delimiter in candidates) {
            val counts = sampleLines.map { countDelimiterInLine(it, delimiter) }
            val maxCount = counts.maxOrNull() ?: 0

            if (maxCount == 0) continue

            // Score: total occurrences * consistency bonus
            // Consistency = how many lines have the same count as the first line
            val modeCount = counts.groupBy { it }.maxByOrNull { it.value.size }?.value?.size ?: 0
            val score = maxCount * 100 + modeCount * 10

            if (score > bestScore) {
                bestScore = score
                bestDelimiter = delimiter
            }
        }

        return bestDelimiter
    }

    /**
     * Count how many times a delimiter appears in a line, respecting quoted fields.
     */
    private fun countDelimiterInLine(line: String, delimiter: Char): Int {
        var count = 0
        var inQuotes = false
        for (ch in line) {
            when {
                ch == '"' -> inQuotes = !inQuotes
                ch == delimiter && !inQuotes -> count++
            }
        }
        return count
    }

    /**
     * Parse a single CSV line into a list of field values, handling quoted fields
     * (including escaped quotes as "").
     */
    private fun parseCsvLine(line: String, delimiter: Char): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0

        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && !inQuotes -> {
                    inQuotes = true
                }
                ch == '"' && inQuotes -> {
                    // Check for escaped quote ""
                    if (i + 1 < line.length && line[i + 1] == '"') {
                        current.append('"')
                        i++ // skip next quote
                    } else {
                        inQuotes = false
                    }
                }
                ch == delimiter && !inQuotes -> {
                    fields.add(current.toString().trim())
                    current.clear()
                }
                else -> {
                    current.append(ch)
                }
            }
            i++
        }

        // Add the last field
        fields.add(current.toString().trim())

        return fields
    }
}
