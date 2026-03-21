package forge.index

import forge.workspace.Database
import forge.workspace.EntityRecord

/**
 * Uses the project index to build targeted context for LLM prompts.
 * Supplements embedding-based retrieval with entity-graph-aware context.
 */
class PromptContextRetrievalService(
    private val db: Database,
    private val queryService: IndexQueryService
) {

    /**
     * Find relevant context for a query by matching entity names
     * and expanding through relationships.
     */
    fun findContextForQuery(
        query: String,
        maxEntities: Int = 20,
        includeRelated: Boolean = true
    ): IndexBasedContext {
        val words = query.split(Regex("""\s+""")).filter { it.length >= 3 }
        val matchedEntities = mutableSetOf<EntityRecord>()

        // Search for entities matching query terms
        for (word in words) {
            val entities = db.searchEntities(word, limit = 10)
            matchedEntities.addAll(entities)
            if (matchedEntities.size >= maxEntities) break
        }

        // Expand with related entities
        val relatedEntities = mutableSetOf<EntityRecord>()
        if (includeRelated) {
            for (entity in matchedEntities.take(10)) {
                val outgoing = db.getRelationshipsBySource(entity.id)
                for (rel in outgoing.take(5)) {
                    rel.targetEntityId?.let { targetId ->
                        db.getEntityById(targetId)?.let { relatedEntities.add(it) }
                    }
                }
                val incoming = db.getRelationshipsByTarget(entity.id)
                for (rel in incoming.take(5)) {
                    db.getEntityById(rel.sourceEntityId)?.let { relatedEntities.add(it) }
                }
            }
        }

        val allEntities = (matchedEntities + relatedEntities).take(maxEntities)

        // Build code snippets from entity signatures
        val snippets = allEntities.map { entity ->
            val file = db.getFileById(entity.fileId)
            "${entity.entityType} ${entity.qualifiedName ?: entity.name} " +
            "(${file?.relativePath}:${entity.startLine}-${entity.endLine})"
        }

        return IndexBasedContext(
            entities = allEntities.toList(),
            snippets = snippets,
            entityCount = allEntities.size
        )
    }

    /**
     * Get full context for a specific entity: its callers, callees, tests, parent chain.
     */
    fun getEntityContext(entityId: Int): EntityContext? {
        val entity = db.getEntityById(entityId) ?: return null

        val callers = db.getRelationshipsByTarget(entityId)
            .filter { it.relationship == "calls" }
            .mapNotNull { db.getEntityById(it.sourceEntityId) }

        val callees = db.getRelationshipsBySource(entityId)
            .filter { it.relationship == "calls" }
            .mapNotNull { it.targetEntityId?.let { id -> db.getEntityById(id) } }

        val parentChain = mutableListOf<EntityRecord>()
        var parentId = entity.parentEntityId
        while (parentId != null) {
            val parent = db.getEntityById(parentId) ?: break
            parentChain.add(parent)
            parentId = parent.parentEntityId
        }

        val tests = queryService.getTestsForSource(entity.name)

        return EntityContext(
            entity = entity,
            parentChain = parentChain,
            callers = callers,
            callees = callees,
            tests = tests
        )
    }
}

data class IndexBasedContext(
    val entities: List<EntityRecord>,
    val snippets: List<String>,
    val entityCount: Int
)

data class EntityContext(
    val entity: EntityRecord,
    val parentChain: List<EntityRecord>,
    val callers: List<EntityRecord>,
    val callees: List<EntityRecord>,
    val tests: List<EntityRecord>
)
