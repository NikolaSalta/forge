package forge.files

import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.ss.usermodel.WorkbookFactory
import java.nio.file.Files
import java.nio.file.Path
import java.text.SimpleDateFormat

/**
 * Extracts tabular data from XLSX/XLS spreadsheet files using Apache POI.
 * Each sheet is converted to a Markdown table.
 */
class XlsxExtractor : FileExtractor {

    override fun supportedTypes(): Set<FileType> = setOf(FileType.XLSX)

    override fun extract(file: Path): ExtractionResult {
        val workbook = Files.newInputStream(file).use { stream ->
            WorkbookFactory.create(stream)
        }

        return workbook.use { wb ->
            val tables = mutableListOf<String>()
            val metadata = mutableMapOf<String, String>()
            val sheetNames = mutableListOf<String>()

            for (sheetIndex in 0 until wb.numberOfSheets) {
                val sheet = wb.getSheetAt(sheetIndex)
                val sheetName = sheet.sheetName ?: "Sheet${sheetIndex + 1}"
                sheetNames.add(sheetName)

                // Collect all row data
                val rowData = mutableListOf<List<String>>()
                var maxCols = 0

                for (row in sheet) {
                    val cells = mutableListOf<String>()
                    val lastCellNum = row.lastCellNum.toInt()
                    if (lastCellNum > maxCols) maxCols = lastCellNum

                    for (colIdx in 0 until lastCellNum) {
                        val cell = row.getCell(colIdx)
                        cells.add(formatCellValue(cell))
                    }
                    rowData.add(cells)
                }

                if (rowData.isEmpty() || maxCols == 0) continue

                // Pad all rows to uniform column count
                val paddedRows = rowData.map { row ->
                    if (row.size < maxCols) {
                        row + List(maxCols - row.size) { "" }
                    } else {
                        row
                    }
                }

                // Build markdown table
                val tableBuilder = StringBuilder()

                // Sheet header
                if (wb.numberOfSheets > 1) {
                    tableBuilder.appendLine("**$sheetName**")
                    tableBuilder.appendLine()
                }

                if (paddedRows.isNotEmpty()) {
                    // First row as header
                    val headerRow = paddedRows[0]
                    tableBuilder.appendLine("| ${headerRow.joinToString(" | ")} |")
                    tableBuilder.appendLine("| ${headerRow.joinToString(" | ") { "---" }} |")

                    // Data rows
                    for (i in 1 until paddedRows.size) {
                        tableBuilder.appendLine("| ${paddedRows[i].joinToString(" | ")} |")
                    }
                }

                val tableStr = tableBuilder.toString().trim()
                if (tableStr.isNotBlank()) {
                    tables.add(tableStr)
                }
            }

            metadata["sheets"] = wb.numberOfSheets.toString()
            metadata["sheetNames"] = sheetNames.joinToString(", ")

            ExtractionResult(
                text = "",
                tables = tables,
                metadata = metadata,
                sourceFile = file.fileName.toString(),
                mimeType = FileType.XLSX.mimeType
            )
        }
    }

    /**
     * Format a cell value to its string representation, handling various cell types.
     */
    private fun formatCellValue(cell: org.apache.poi.ss.usermodel.Cell?): String {
        if (cell == null) return ""

        return try {
            when (cell.cellType) {
                CellType.STRING -> cell.stringCellValue ?: ""
                CellType.NUMERIC -> {
                    if (DateUtil.isCellDateFormatted(cell)) {
                        val date = cell.dateCellValue
                        if (date != null) {
                            SimpleDateFormat("yyyy-MM-dd").format(date)
                        } else {
                            ""
                        }
                    } else {
                        val numValue = cell.numericCellValue
                        // Format as integer if no fractional part
                        if (numValue == numValue.toLong().toDouble()) {
                            numValue.toLong().toString()
                        } else {
                            numValue.toString()
                        }
                    }
                }
                CellType.BOOLEAN -> cell.booleanCellValue.toString()
                CellType.FORMULA -> {
                    try {
                        // Try to get the cached value
                        when (cell.cachedFormulaResultType) {
                            CellType.STRING -> cell.stringCellValue ?: ""
                            CellType.NUMERIC -> {
                                val numValue = cell.numericCellValue
                                if (numValue == numValue.toLong().toDouble()) {
                                    numValue.toLong().toString()
                                } else {
                                    numValue.toString()
                                }
                            }
                            CellType.BOOLEAN -> cell.booleanCellValue.toString()
                            else -> cell.cellFormula ?: ""
                        }
                    } catch (_: Exception) {
                        cell.cellFormula ?: ""
                    }
                }
                CellType.BLANK -> ""
                CellType.ERROR -> "#ERR"
                else -> ""
            }
        } catch (_: Exception) {
            ""
        }
    }
}
