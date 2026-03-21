package forge.index

import forge.workspace.*

/**
 * Read-only query API for the project index.
 * Provides entity search, relationship traversal, and aggregate statistics.
 */
class IndexQueryService(private val db: Database) {

    // -- Entity queries --

    fun getEntityByName(name: String): List<EntityRecord> = db.getEntityByName(name)
    fun getEntityByQualifiedName(qualifiedName: String): EntityRecord? = db.getEntityByQualifiedName(qualifiedName)
    fun getEntitiesByFile(fileId: Int): List<EntityRecord> = db.getEntitiesByFile(fileId)
    fun getEntitiesByModule(moduleId: Int): List<EntityRecord> = db.getEntitiesByModule(moduleId)
    fun getEntitiesByType(entityType: String, limit: Int = 100): List<EntityRecord> = db.getEntitiesByType(entityType, limit)
    fun searchEntities(query: String, limit: Int = 50): List<EntityRecord> = db.searchEntities(query, limit)
    fun getEntityById(id: Int): EntityRecord? = db.getEntityById(id)

    // -- Relationship queries --

    fun getOutgoingRelationships(entityId: Int): List<EntityRelationshipRecord> = db.getRelationshipsBySource(entityId)
    fun getIncomingRelationships(entityId: Int): List<EntityRelationshipRecord> = db.getRelationshipsByTarget(entityId)

    fun getCallGraph(entityId: Int, depth: Int = 2): Map<Int, List<EntityRelationshipRecord>> {
        val result = mutableMapOf<Int, List<EntityRelationshipRecord>>()
        val visited = mutableSetOf<Int>()
        val queue = ArrayDeque<Pair<Int, Int>>() // (entityId, currentDepth)
        queue.add(Pair(entityId, 0))

        while (queue.isNotEmpty()) {
            val (currentId, currentDepth) = queue.removeFirst()
            if (currentId in visited || currentDepth >= depth) continue
            visited.add(currentId)

            val outgoing = db.getRelationshipsBySource(currentId)
                .filter { it.relationship == "calls" }
            result[currentId] = outgoing

            for (rel in outgoing) {
                rel.targetEntityId?.let { targetId ->
                    if (targetId !in visited) {
                        queue.add(Pair(targetId, currentDepth + 1))
                    }
                }
            }
        }
        return result
    }

    fun getInheritanceHierarchy(entityId: Int): List<EntityRecord> {
        val result = mutableListOf<EntityRecord>()
        val visited = mutableSetOf<Int>()
        var currentId: Int? = entityId

        while (currentId != null && currentId !in visited) {
            visited.add(currentId)
            val entity = db.getEntityById(currentId) ?: break
            result.add(entity)

            val extendsRel = db.getRelationshipsBySource(currentId)
                .firstOrNull { it.relationship == "extends" }
            currentId = extendsRel?.targetEntityId
        }
        return result
    }

    // -- Line index queries --

    fun getEntityAtLine(fileId: Int, lineNum: Int): EntityRecord? {
        val lineRecord = db.getLineIndex(fileId, lineNum) ?: return null
        return lineRecord.entityId?.let { db.getEntityById(it) }
    }

    // -- Test mapping --

    fun getTestsForSource(sourceEntityName: String): List<EntityRecord> {
        // Find test entities that reference the source entity
        val testEntities = db.getEntitiesByType("function", limit = 10000)
            .filter { it.name.contains(sourceEntityName, ignoreCase = true) }
        return testEntities
    }

    // -- Aggregate statistics --

    fun getIndexStats(): IndexStats {
        val entityCount = db.getEntityCount()
        val relationshipCount = db.getRelationshipCount()
        val lineCount = db.getLineIndexCount()
        val depEdgeCount = db.getDependencyEdgeCount()

        val lastMeta = db.getLatestIndexMetadata("full")
            ?: db.getLatestIndexMetadata("incremental")

        // Entity breakdown by type — use COUNT(*) queries, not row loading
        val entitiesByType = mutableMapOf<String, Int>()
        for (type in listOf("class", "function", "method", "field", "interface", "enum", "constant", "object", "trait", "struct", "annotation", "data_class")) {
            val count = db.countEntitiesByType(type)
            if (count > 0 || type in listOf("class", "function", "method", "field")) {
                entitiesByType[type] = count
            }
        }

        // Relationship breakdown — use COUNT(*) queries
        val relationshipsByType = mutableMapOf<String, Int>()
        for (type in listOf("imports", "calls", "extends", "implements", "overrides", "contains", "tests")) {
            val count = db.countRelationshipsByType(type)
            if (count > 0) {
                relationshipsByType[type] = count
            }
        }

        val classificationOverview = db.getClassificationOverview()

        return IndexStats(
            totalEntities = entityCount,
            totalRelationships = relationshipCount,
            totalLinesIndexed = lineCount,
            totalDependencyEdges = depEdgeCount,
            entitiesByType = entitiesByType,
            relationshipsByType = relationshipsByType,
            filesByClassification = classificationOverview,
            lastIndexedAt = lastMeta?.completedAt,
            indexStatus = lastMeta?.status ?: "not_built"
        )
    }
}

data class IndexStats(
    val totalEntities: Int,
    val totalRelationships: Int,
    val totalLinesIndexed: Int,
    val totalDependencyEdges: Int,
    val entitiesByType: Map<String, Int>,
    val relationshipsByType: Map<String, Int>,
    val filesByClassification: Map<String, Int>,
    val lastIndexedAt: String?,
    val indexStatus: String
)
