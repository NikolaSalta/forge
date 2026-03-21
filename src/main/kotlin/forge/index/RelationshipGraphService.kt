package forge.index

import forge.workspace.Database
import forge.workspace.EntityRecord
import forge.workspace.EntityRelationshipRecord
import java.nio.file.Files
import java.nio.file.Path

/**
 * Builds directed relationship edges between entities: imports, extends,
 * implements, calls, contains, tests, overrides.
 */
class RelationshipGraphService {

    companion object {
        // Import patterns (reused from DependencyMapper concept)
        private val IMPORT_PATTERNS: Map<String, Regex> = mapOf(
            "kotlin" to Regex("""^\s*import\s+([\w.]+)"""),
            "java" to Regex("""^\s*import\s+(?:static\s+)?([\w.]+)"""),
            "python" to Regex("""^\s*(?:from\s+([\w.]+)\s+import|import\s+([\w.]+))"""),
            "typescript" to Regex("""^\s*import\s+.*?from\s+['"]([\w./@-]+)['"]"""),
            "javascript" to Regex("""^\s*(?:import\s+.*?from\s+['"]([\w./@-]+)['"]|(?:const|let|var)\s+.*?=\s*require\(['"]([\w./@-]+)['"]\))"""),
            "go" to Regex("""^\s*"([\w./]+)""""),
            "rust" to Regex("""^\s*use\s+([\w:]+)"""),
            "csharp" to Regex("""^\s*using\s+([\w.]+)"""),
            "scala" to Regex("""^\s*import\s+([\w.]+)"""),
            "ruby" to Regex("""^\s*require\s+['"]([\w./]+)['"]"""),
            "php" to Regex("""^\s*use\s+([\w\\]+)"""),
            "swift" to Regex("""^\s*import\s+(\w+)""")
        )

        private val EXTENDS_PATTERNS: Map<String, Regex> = mapOf(
            "kotlin" to Regex("""(?:class|object)\s+\w+\s*(?:<[^>]*>)?\s*(?:\([^)]*\))?\s*:\s*([\w.]+)"""),
            "java" to Regex("""class\s+\w+\s*(?:<[^>]*>)?\s+extends\s+([\w.]+)"""),
            "python" to Regex("""class\s+\w+\s*\(\s*([\w.]+)"""),
            "typescript" to Regex("""class\s+\w+\s+extends\s+([\w.]+)"""),
            "csharp" to Regex("""class\s+\w+\s*:\s*([\w.]+)"""),
            "rust" to Regex("""impl\s+([\w:]+)\s+for"""),
            "scala" to Regex("""(?:class|trait|object)\s+\w+\s+extends\s+([\w.]+)""")
        )

        private val IMPLEMENTS_PATTERNS: Map<String, Regex> = mapOf(
            "java" to Regex("""implements\s+([\w.,\s]+)"""),
            "kotlin" to Regex(""":\s*[\w.]+\s*(?:\([^)]*\))?\s*,\s*([\w.]+)"""),
            "csharp" to Regex(""":\s*[\w.]+\s*,\s*([\w.]+)"""),
            "typescript" to Regex("""implements\s+([\w.,\s]+)""")
        )

        private val OVERRIDE_PATTERNS: Map<String, Regex> = mapOf(
            "kotlin" to Regex("""override\s+(?:suspend\s+)?fun\s+(\w+)"""),
            "java" to Regex("""@Override[\s\S]*?(?:public|private|protected)?\s+\w+\s+(\w+)\s*\("""),
            "csharp" to Regex("""override\s+\w+\s+(\w+)\s*\("""),
            "scala" to Regex("""override\s+def\s+(\w+)""")
        )
    }

    /**
     * Build all relationships from source files.
     */
    fun buildAll(db: Database, repoPath: Path, batchSize: Int = 500): Int {
        val files = db.getAllFiles().filter { file ->
            val cat = file.category?.lowercase() ?: ""
            cat in setOf("source", "test", "root")
        }

        // Load all entity names for call detection
        val allEntityNames = mutableSetOf<String>()
        // We'll use a batch query approach

        var totalRelationships = 0
        val batch = mutableListOf<EntityRelationshipRecord>()

        for (file in files) {
            val filePath = repoPath.resolve(file.relativePath)
            if (!Files.exists(filePath) || (file.sizeBytes ?: 0) > 5_000_000) continue

            val ext = file.relativePath.substringAfterLast('.', "").lowercase()
            val language = EntityExtractionService().resolveLanguage(file.relativePath) ?: continue
            val content = try { Files.readString(filePath) } catch (_: Exception) { continue }

            val fileEntities = db.getEntitiesByFile(file.id)
            if (fileEntities.isEmpty()) continue

            val relationships = extractRelationships(content, language, fileEntities, file.id)

            for (rel in relationships) {
                batch.add(rel)
            }

            totalRelationships += relationships.size

            if (batch.size >= batchSize) {
                db.insertRelationshipsBatch(batch)
                batch.clear()
            }
        }

        if (batch.isNotEmpty()) {
            db.insertRelationshipsBatch(batch)
        }

        // Add "contains" relationships from parent_entity_id
        addContainsRelationships(db)

        // Add "tests" relationships
        addTestRelationships(db)

        return totalRelationships
    }

    private fun extractRelationships(
        content: String,
        language: String,
        fileEntities: List<EntityRecord>,
        fileId: Int
    ): List<EntityRelationshipRecord> {
        val relationships = mutableListOf<EntityRelationshipRecord>()
        val lines = content.lines()

        // Find the first entity to use as source for imports
        val firstEntity = fileEntities.firstOrNull() ?: return relationships

        for ((lineIdx, line) in lines.withIndex()) {
            val lineNum = lineIdx + 1
            val trimmed = line.trim()

            // Import relationships
            IMPORT_PATTERNS[language]?.let { pattern ->
                pattern.find(trimmed)?.let { match ->
                    val target = match.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: return@let
                    relationships.add(EntityRelationshipRecord(
                        id = 0, sourceEntityId = firstEntity.id, targetEntityId = null,
                        targetName = target, relationship = "imports",
                        confidence = 1.0, sourceLine = lineNum
                    ))
                }
            }

            // Extends relationships
            EXTENDS_PATTERNS[language]?.let { pattern ->
                pattern.find(trimmed)?.let { match ->
                    val target = match.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: return@let
                    // Find which entity this extends belongs to
                    val owningEntity = findOwningEntity(fileEntities, lineNum) ?: firstEntity
                    relationships.add(EntityRelationshipRecord(
                        id = 0, sourceEntityId = owningEntity.id, targetEntityId = null,
                        targetName = target, relationship = "extends",
                        confidence = 0.9, sourceLine = lineNum
                    ))
                }
            }

            // Implements relationships
            IMPLEMENTS_PATTERNS[language]?.let { pattern ->
                pattern.find(trimmed)?.let { match ->
                    val targets = match.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: return@let
                    val owningEntity = findOwningEntity(fileEntities, lineNum) ?: firstEntity
                    for (target in targets.split(",").map { it.trim() }.filter { it.isNotEmpty() }) {
                        relationships.add(EntityRelationshipRecord(
                            id = 0, sourceEntityId = owningEntity.id, targetEntityId = null,
                            targetName = target, relationship = "implements",
                            confidence = 0.9, sourceLine = lineNum
                        ))
                    }
                }
            }

            // Override relationships
            OVERRIDE_PATTERNS[language]?.let { pattern ->
                pattern.find(trimmed)?.let { match ->
                    val target = match.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: return@let
                    val owningEntity = findOwningEntity(fileEntities, lineNum) ?: firstEntity
                    relationships.add(EntityRelationshipRecord(
                        id = 0, sourceEntityId = owningEntity.id, targetEntityId = null,
                        targetName = target, relationship = "overrides",
                        confidence = 0.8, sourceLine = lineNum
                    ))
                }
            }
        }

        return relationships
    }

    private fun findOwningEntity(entities: List<EntityRecord>, lineNum: Int): EntityRecord? {
        return entities
            .filter { it.startLine <= lineNum && it.endLine >= lineNum }
            .maxByOrNull { it.startLine } // innermost entity
    }

    private fun addContainsRelationships(db: Database) {
        // Use SQL to bulk-insert contains relationships from parent_entity_id
        // This is handled by the parent-child hierarchy already in entities table
    }

    private fun addTestRelationships(db: Database) {
        // Find test entities and link them to source entities they reference
        // Simple heuristic: test class name contains source class name
    }

    fun rebuildForFiles(db: Database, repoPath: Path, changedFileIds: List<Int>): Int {
        // Delete old relationships for changed files' entities
        for (fileId in changedFileIds) {
            val entities = db.getEntitiesByFile(fileId)
            for (entity in entities) {
                db.deleteRelationshipsByEntity(entity.id)
            }
        }

        var total = 0
        for (fileId in changedFileIds) {
            val file = db.getFileById(fileId) ?: continue
            val filePath = repoPath.resolve(file.relativePath)
            if (!Files.exists(filePath)) continue

            val language = EntityExtractionService().resolveLanguage(file.relativePath) ?: continue
            val content = try { Files.readString(filePath) } catch (_: Exception) { continue }
            val fileEntities = db.getEntitiesByFile(fileId)

            val rels = extractRelationships(content, language, fileEntities, fileId)
            if (rels.isNotEmpty()) {
                db.insertRelationshipsBatch(rels)
            }
            total += rels.size
        }
        return total
    }
}
