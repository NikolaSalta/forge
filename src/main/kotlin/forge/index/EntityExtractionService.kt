package forge.index

import forge.workspace.Database
import forge.workspace.EntityRecord
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Extracts code entities (classes, functions, methods, fields, etc.) from source files
 * using regex-based pattern matching. Extends the Chunker's boundary detection with
 * finer-grained entity extraction including visibility, nesting, and signatures.
 */
class EntityExtractionService {

    data class ExtractedEntity(
        val entityType: String,
        val name: String,
        val qualifiedName: String?,
        val visibility: String?,
        val signature: String,
        val startLine: Int,
        val endLine: Int,
        val parentIndex: Int?,  // index in the flat list for parent tracking
        val language: String?
    )

    companion object {
        // Extended entity patterns per language - more comprehensive than Chunker's BOUNDARY_PATTERNS
        // Each pattern captures: optional visibility, entity keyword, entity name
        val ENTITY_PATTERNS: Map<String, List<EntityPattern>> = mapOf(
            "kotlin" to listOf(
                EntityPattern(Regex("""^\s*(public|private|protected|internal)?\s*(data\s+class|sealed\s+class|enum\s+class|abstract\s+class|open\s+class|class|interface|object|annotation\s+class)\s+(\w+)"""), "class"),
                EntityPattern(Regex("""^\s*(public|private|protected|internal|override)?\s*(suspend\s+)?fun\s+(\w+)"""), "function"),
                EntityPattern(Regex("""^\s*(public|private|protected|internal)?\s*(val|var)\s+(\w+)"""), "field"),
                EntityPattern(Regex("""^\s*(const\s+val)\s+(\w+)"""), "constant"),
                EntityPattern(Regex("""^\s*@(\w+)"""), "annotation")
            ),
            "java" to listOf(
                EntityPattern(Regex("""^\s*(public|private|protected)?\s*(static\s+)?(abstract\s+)?(class|interface|enum|@interface)\s+(\w+)"""), "class"),
                EntityPattern(Regex("""^\s*(public|private|protected)?\s*(static\s+)?(synchronized\s+)?(?:[\w<>\[\],\s]+)\s+(\w+)\s*\("""), "function"),
                EntityPattern(Regex("""^\s*(public|private|protected)?\s*(static\s+)?(final\s+)?(?:\w+)\s+(\w+)\s*[=;]"""), "field"),
                EntityPattern(Regex("""^\s*@(\w+)"""), "annotation")
            ),
            "python" to listOf(
                EntityPattern(Regex("""^\s*(class)\s+(\w+)"""), "class"),
                EntityPattern(Regex("""^\s*(async\s+)?def\s+(\w+)"""), "function"),
                EntityPattern(Regex("""^\s*(\w+)\s*:\s*\w+\s*="""), "field"),
                EntityPattern(Regex("""^([A-Z][A-Z_0-9]+)\s*="""), "constant"),
                EntityPattern(Regex("""^\s*@(\w+)"""), "annotation")
            ),
            "typescript" to listOf(
                EntityPattern(Regex("""^\s*(export\s+)?(abstract\s+)?(class|interface|enum|type)\s+(\w+)"""), "class"),
                EntityPattern(Regex("""^\s*(export\s+)?(async\s+)?function\s+(\w+)"""), "function"),
                EntityPattern(Regex("""^\s*(export\s+)?(const|let|var)\s+(\w+)"""), "field"),
                EntityPattern(Regex("""^\s*@(\w+)"""), "annotation")
            ),
            "javascript" to listOf(
                EntityPattern(Regex("""^\s*(export\s+)?(class)\s+(\w+)"""), "class"),
                EntityPattern(Regex("""^\s*(export\s+)?(async\s+)?function\s+(\w+)"""), "function"),
                EntityPattern(Regex("""^\s*(export\s+)?(const|let|var)\s+(\w+)"""), "field")
            ),
            "go" to listOf(
                EntityPattern(Regex("""^\s*type\s+(\w+)\s+(struct|interface)"""), "class"),
                EntityPattern(Regex("""^\s*func\s+(?:\([^)]+\)\s+)?(\w+)"""), "function"),
                EntityPattern(Regex("""^\s*(const|var)\s+(\w+)"""), "field")
            ),
            "rust" to listOf(
                EntityPattern(Regex("""^\s*(pub\s+)?(struct|enum|trait|impl)\s+(\w+)"""), "class"),
                EntityPattern(Regex("""^\s*(pub\s+)?(async\s+)?fn\s+(\w+)"""), "function"),
                EntityPattern(Regex("""^\s*(pub\s+)?(static\s+)?(const|let|let\s+mut)\s+(\w+)"""), "field")
            ),
            "csharp" to listOf(
                EntityPattern(Regex("""^\s*(public|private|protected|internal)?\s*(static\s+)?(abstract\s+)?(class|interface|struct|enum|record)\s+(\w+)"""), "class"),
                EntityPattern(Regex("""^\s*(public|private|protected|internal)?\s*(static\s+)?(async\s+)?(?:[\w<>\[\]]+)\s+(\w+)\s*\("""), "function"),
                EntityPattern(Regex("""^\s*(public|private|protected|internal)?\s*(static\s+)?(readonly\s+)?(?:\w+)\s+(\w+)\s*[{=;]"""), "field")
            ),
            "cpp" to listOf(
                EntityPattern(Regex("""^\s*(class|struct|enum\s+class|enum|namespace)\s+(\w+)"""), "class"),
                EntityPattern(Regex("""^\s*(?:[\w:*&<>]+)\s+(\w+)\s*\("""), "function"),
                EntityPattern(Regex("""^\s*(static\s+)?(const\s+)?(?:\w+)\s+(\w+)\s*[=;]"""), "field")
            ),
            "scala" to listOf(
                EntityPattern(Regex("""^\s*(case\s+class|class|trait|object|abstract\s+class|sealed\s+trait)\s+(\w+)"""), "class"),
                EntityPattern(Regex("""^\s*(def)\s+(\w+)"""), "function"),
                EntityPattern(Regex("""^\s*(val|var|lazy\s+val)\s+(\w+)"""), "field")
            ),
            "ruby" to listOf(
                EntityPattern(Regex("""^\s*(class|module)\s+(\w+)"""), "class"),
                EntityPattern(Regex("""^\s*def\s+(\w+[?!]?)"""), "function"),
                EntityPattern(Regex("""^\s*(attr_reader|attr_writer|attr_accessor)\s+:(\w+)"""), "field")
            ),
            "php" to listOf(
                EntityPattern(Regex("""^\s*(abstract\s+)?(class|interface|trait|enum)\s+(\w+)"""), "class"),
                EntityPattern(Regex("""^\s*(public|private|protected)?\s*(static\s+)?function\s+(\w+)"""), "function"),
                EntityPattern(Regex("""^\s*(public|private|protected)?\s*(\$\w+)"""), "field")
            ),
            "swift" to listOf(
                EntityPattern(Regex("""^\s*(public|private|internal|open|fileprivate)?\s*(class|struct|enum|protocol|extension)\s+(\w+)"""), "class"),
                EntityPattern(Regex("""^\s*(public|private|internal|open|fileprivate)?\s*(static\s+)?func\s+(\w+)"""), "function"),
                EntityPattern(Regex("""^\s*(public|private|internal)?\s*(static\s+)?(let|var)\s+(\w+)"""), "field")
            )
        )

        private val EXTENSION_TO_LANGUAGE: Map<String, String> = mapOf(
            "kt" to "kotlin", "kts" to "kotlin", "java" to "java",
            "ts" to "typescript", "tsx" to "typescript",
            "js" to "javascript", "jsx" to "javascript",
            "py" to "python", "go" to "go", "rs" to "rust",
            "cs" to "csharp", "cpp" to "cpp", "cc" to "cpp", "cxx" to "cpp",
            "c" to "c", "h" to "c", "hpp" to "cpp",
            "scala" to "scala", "rb" to "ruby", "php" to "php", "swift" to "swift"
        )
    }

    data class EntityPattern(val regex: Regex, val defaultType: String)

    fun resolveLanguage(filePath: String): String? {
        val ext = filePath.substringAfterLast('.', "").lowercase()
        return EXTENSION_TO_LANGUAGE[ext]
    }

    /**
     * Extract entities from a single file's content.
     */
    fun extractEntities(
        content: String,
        language: String,
        fileId: Int,
        moduleId: Int?
    ): List<ExtractedEntity> {
        val patterns = ENTITY_PATTERNS[language] ?: return emptyList()
        val lines = content.lines()
        val entities = mutableListOf<ExtractedEntity>()

        // Track brace depth for determining entity end lines
        val braceStack = mutableListOf<Int>() // stack of entity indices
        var braceDepthAtEntityStart = mutableListOf<Int>() // brace depth when entity started
        var currentBraceDepth = 0

        for ((lineIdx, line) in lines.withIndex()) {
            val lineNum = lineIdx + 1
            val trimmed = line.trim()

            // Skip comments and blank lines for entity detection
            if (trimmed.startsWith("//") || trimmed.startsWith("#") || trimmed.startsWith("*") || trimmed.isEmpty()) {
                // Update brace depth even in comments might be wrong, but simple enough
                continue
            }

            // Count braces for scope tracking
            val openBraces = line.count { it == '{' }
            val closeBraces = line.count { it == '}' }

            // Check for entity patterns
            for (pattern in patterns) {
                if (pattern.regex.containsMatchIn(trimmed)) {
                    val match = pattern.regex.find(trimmed) ?: continue

                    // Extract entity name (last captured group is usually the name)
                    val groups = match.groupValues.filter { it.isNotEmpty() }
                    val name = groups.lastOrNull()?.trim() ?: continue
                    if (name.length < 2 || name == "if" || name == "for" || name == "while" || name == "return") continue

                    // Extract visibility
                    val visibility = extractVisibility(trimmed, language)

                    // Determine entity type more precisely
                    val entityType = refineEntityType(pattern.defaultType, trimmed, language)

                    // Determine parent (innermost enclosing entity)
                    val parentIdx = braceStack.lastOrNull()

                    val entity = ExtractedEntity(
                        entityType = entityType,
                        name = name,
                        qualifiedName = null, // resolved later
                        visibility = visibility,
                        signature = trimmed.take(200),
                        startLine = lineNum,
                        endLine = lineNum, // updated when scope closes
                        parentIndex = parentIdx,
                        language = language
                    )
                    entities.add(entity)

                    // If this line opens a scope, track it
                    if (openBraces > closeBraces || trimmed.endsWith(":") || language == "python") {
                        braceStack.add(entities.size - 1)
                        braceDepthAtEntityStart.add(currentBraceDepth)
                    }

                    break // Only match first pattern per line
                }
            }

            // Update brace depth
            currentBraceDepth += openBraces - closeBraces

            // Close entities whose scope has ended
            while (braceStack.isNotEmpty() && braceDepthAtEntityStart.isNotEmpty()
                   && currentBraceDepth <= braceDepthAtEntityStart.last()) {
                val entityIdx = braceStack.removeAt(braceStack.size - 1)
                braceDepthAtEntityStart.removeAt(braceDepthAtEntityStart.size - 1)
                if (entityIdx < entities.size) {
                    entities[entityIdx] = entities[entityIdx].copy(endLine = lineNum)
                }
            }
        }

        // Close any remaining open entities at end of file
        for (entityIdx in braceStack) {
            if (entityIdx < entities.size) {
                entities[entityIdx] = entities[entityIdx].copy(endLine = lines.size)
            }
        }

        // Build qualified names
        return resolveQualifiedNames(entities)
    }

    private fun extractVisibility(line: String, language: String): String? {
        return when (language) {
            "kotlin", "java", "csharp", "swift" -> {
                when {
                    line.contains("public ") || line.contains("public\t") -> "public"
                    line.contains("private ") -> "private"
                    line.contains("protected ") -> "protected"
                    line.contains("internal ") -> "internal"
                    else -> "default"
                }
            }
            "python" -> {
                val name = line.trim().substringAfter("def ").substringAfter("class ").substringBefore("(").substringBefore(":").trim()
                when {
                    name.startsWith("__") && !name.endsWith("__") -> "private"
                    name.startsWith("_") -> "protected"
                    else -> "public"
                }
            }
            "typescript", "javascript" -> {
                when {
                    line.contains("export ") -> "public"
                    line.contains("private ") -> "private"
                    line.contains("protected ") -> "protected"
                    else -> "default"
                }
            }
            else -> null
        }
    }

    private fun refineEntityType(defaultType: String, line: String, language: String): String {
        if (defaultType == "class") {
            return when {
                line.contains("interface ") -> "interface"
                line.contains("enum ") -> "enum"
                line.contains("struct ") -> "struct"
                line.contains("trait ") -> "trait"
                line.contains("protocol ") -> "protocol"
                line.contains("object ") && language == "kotlin" -> "object"
                line.contains("annotation ") -> "annotation"
                line.contains("abstract ") -> "abstract_class"
                line.contains("data class") -> "data_class"
                line.contains("sealed ") -> "sealed_class"
                line.contains("record ") -> "record"
                line.contains("module ") && language == "ruby" -> "module"
                line.contains("namespace ") -> "namespace"
                else -> "class"
            }
        }
        if (defaultType == "function") {
            // Check if it's a method (inside a class)
            if (line.startsWith("  ") || line.startsWith("\t")) {
                return "method"
            }
        }
        return defaultType
    }

    private fun resolveQualifiedNames(entities: List<ExtractedEntity>): List<ExtractedEntity> {
        val result = entities.toMutableList()
        for (i in result.indices) {
            val entity = result[i]
            val parts = mutableListOf(entity.name)
            var parentIdx = entity.parentIndex
            while (parentIdx != null && parentIdx < result.size) {
                parts.add(0, result[parentIdx].name)
                parentIdx = result[parentIdx].parentIndex
            }
            result[i] = entity.copy(qualifiedName = parts.joinToString("."))
        }
        return result
    }

    /**
     * Extract entities from all source/test files and persist to database.
     */
    fun extractAll(
        db: Database,
        repoPath: Path,
        batchSize: Int = 500,
        onProgress: ((filesProcessed: Int, entitiesFound: Int) -> Unit)? = null
    ): Int {
        val files = db.getAllFiles().filter { file ->
            val cat = file.category?.lowercase() ?: ""
            cat in setOf("source", "test", "root", "build")
        }

        var totalEntities = 0
        var filesProcessed = 0
        val batch = mutableListOf<EntityRecord>()

        for (file in files) {
            val filePath = repoPath.resolve(file.relativePath)
            if (!Files.exists(filePath) || Files.size(filePath) > 5_000_000) continue

            val language = resolveLanguage(file.relativePath) ?: continue
            val content = try { Files.readString(filePath) } catch (_: Exception) { continue }

            val extracted = extractEntities(content, language, file.id, null)

            for (entity in extracted) {
                val parentId: Int? = null // Will be resolved after batch insert
                batch.add(EntityRecord(
                    id = 0,
                    fileId = file.id,
                    moduleId = null,
                    entityType = entity.entityType,
                    name = entity.name,
                    qualifiedName = entity.qualifiedName,
                    parentEntityId = parentId,
                    language = entity.language,
                    visibility = entity.visibility,
                    signature = entity.signature,
                    startLine = entity.startLine,
                    endLine = entity.endLine,
                    sha256 = null
                ))
            }

            totalEntities += extracted.size
            filesProcessed++

            if (batch.size >= batchSize) {
                db.insertEntitiesBatch(batch)
                batch.clear()
            }

            if (filesProcessed % 100 == 0) {
                onProgress?.invoke(filesProcessed, totalEntities)
            }
        }

        if (batch.isNotEmpty()) {
            db.insertEntitiesBatch(batch)
        }

        onProgress?.invoke(filesProcessed, totalEntities)
        return totalEntities
    }

    /**
     * Incremental extraction for changed files only.
     */
    fun extractIncremental(db: Database, repoPath: Path, changedFileIds: List<Int>): Int {
        // Delete old entities for changed files
        for (fileId in changedFileIds) {
            db.deleteEntitiesByFile(fileId)
        }

        var total = 0
        val batch = mutableListOf<EntityRecord>()

        for (fileId in changedFileIds) {
            val file = db.getFileById(fileId) ?: continue
            val filePath = repoPath.resolve(file.relativePath)
            if (!Files.exists(filePath)) continue

            val language = resolveLanguage(file.relativePath) ?: continue
            val content = try { Files.readString(filePath) } catch (_: Exception) { continue }

            val extracted = extractEntities(content, language, file.id, null)
            for (entity in extracted) {
                batch.add(EntityRecord(
                    id = 0, fileId = file.id, moduleId = null,
                    entityType = entity.entityType, name = entity.name,
                    qualifiedName = entity.qualifiedName, parentEntityId = null,
                    language = entity.language, visibility = entity.visibility,
                    signature = entity.signature, startLine = entity.startLine,
                    endLine = entity.endLine, sha256 = null
                ))
            }
            total += extracted.size
        }

        if (batch.isNotEmpty()) {
            db.insertEntitiesBatch(batch)
        }
        return total
    }
}
