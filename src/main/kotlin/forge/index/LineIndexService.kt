package forge.index

import forge.workspace.Database
import forge.workspace.EntityRecord
import forge.workspace.LineIndexRecord
import java.nio.file.Files
import java.nio.file.Path

/**
 * Maps every line of source/test files to its owning entity.
 * Single-pass algorithm: sort entities by startLine, assign each line
 * to the innermost entity whose range contains it.
 */
class LineIndexService {

    fun buildAll(db: Database, repoPath: Path, batchSize: Int = 2000): Int {
        val files = db.getAllFiles().filter { file ->
            val cat = file.category?.lowercase() ?: ""
            cat in setOf("source", "test")
        }

        var totalLines = 0
        val batch = mutableListOf<LineIndexRecord>()

        for (file in files) {
            val filePath = repoPath.resolve(file.relativePath)
            if (!Files.exists(filePath) || (file.sizeBytes ?: 0) > 2_000_000) continue

            val lineCount = file.lineCount ?: continue
            if (lineCount == 0) continue

            val entities = db.getEntitiesByFile(file.id)
            val content = try { Files.readString(filePath) } catch (_: Exception) { continue }
            val lines = content.lines()

            for (lineIdx in lines.indices) {
                val lineNum = lineIdx + 1
                val line = lines[lineIdx]
                val trimmed = line.trim()

                // Find owning entity (innermost)
                val owningEntity = entities
                    .filter { it.startLine <= lineNum && it.endLine >= lineNum }
                    .maxByOrNull { it.startLine }

                // Classify line type
                val lineType = classifyLine(trimmed, owningEntity, lineNum)

                batch.add(LineIndexRecord(
                    id = 0,
                    fileId = file.id,
                    lineNum = lineNum,
                    entityId = owningEntity?.id,
                    lineType = lineType
                ))
                totalLines++

                if (batch.size >= batchSize) {
                    db.insertLineIndexBatch(batch)
                    batch.clear()
                }
            }
        }

        if (batch.isNotEmpty()) {
            db.insertLineIndexBatch(batch)
        }

        return totalLines
    }

    private fun classifyLine(trimmed: String, entity: EntityRecord?, lineNum: Int): String {
        return when {
            trimmed.isEmpty() -> "blank"
            trimmed.startsWith("//") || trimmed.startsWith("#") || trimmed.startsWith("/*") || trimmed.startsWith("*") -> "comment"
            trimmed.startsWith("import ") || trimmed.startsWith("from ") || trimmed.startsWith("require(") || trimmed.startsWith("use ") -> "import"
            trimmed.startsWith("@") -> "annotation"
            entity != null && lineNum == entity.startLine -> "declaration"
            entity != null -> "body"
            else -> "other"
        }
    }

    fun rebuildForFiles(db: Database, repoPath: Path, changedFileIds: List<Int>): Int {
        for (fileId in changedFileIds) {
            db.deleteLineIndexByFile(fileId)
        }

        var total = 0
        val batch = mutableListOf<LineIndexRecord>()

        for (fileId in changedFileIds) {
            val file = db.getFileById(fileId) ?: continue
            val filePath = repoPath.resolve(file.relativePath)
            if (!Files.exists(filePath)) continue

            val content = try { Files.readString(filePath) } catch (_: Exception) { continue }
            val lines = content.lines()
            val entities = db.getEntitiesByFile(fileId)

            for (lineIdx in lines.indices) {
                val lineNum = lineIdx + 1
                val trimmed = lines[lineIdx].trim()
                val owningEntity = entities
                    .filter { it.startLine <= lineNum && it.endLine >= lineNum }
                    .maxByOrNull { it.startLine }

                batch.add(LineIndexRecord(0, fileId, lineNum, owningEntity?.id, classifyLine(trimmed, owningEntity, lineNum)))
                total++
            }
        }

        if (batch.isNotEmpty()) {
            db.insertLineIndexBatch(batch)
        }
        return total
    }
}
