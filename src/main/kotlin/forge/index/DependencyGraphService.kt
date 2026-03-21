package forge.index

import forge.workspace.Database
import forge.workspace.DependencyEdge

/**
 * Builds module/package-level dependency edges from entity relationships.
 * Aggregates import relationships into module-to-module dependency edges with weights.
 */
class DependencyGraphService {

    fun buildGraph(db: Database) {
        db.clearDependencyGraph()

        // Get all import relationships with their source entities
        val importRels = db.getRelationshipsByType("imports", limit = 100_000)

        // Group by source entity's file -> module mapping
        val moduleEdges = mutableMapOf<Pair<String, String>, Int>()

        for (rel in importRels) {
            val sourceEntity = db.getEntityById(rel.sourceEntityId) ?: continue
            val sourceFile = db.getFileById(sourceEntity.fileId) ?: continue

            val sourcePath = extractPackagePath(sourceFile.relativePath)
            val targetPath = rel.targetName

            if (sourcePath != targetPath) {
                val key = Pair(sourcePath, targetPath)
                moduleEdges[key] = (moduleEdges[key] ?: 0) + 1
            }
        }

        // Insert aggregated edges
        for ((edge, weight) in moduleEdges) {
            db.insertDependencyEdge(
                sourceModuleId = null,
                targetModuleId = null,
                sourcePath = edge.first,
                targetPath = edge.second,
                depType = "compile",
                weight = weight
            )
        }
    }

    private fun extractPackagePath(relativePath: String): String {
        // Extract the directory as a "package" path
        val parts = relativePath.split("/")
        return if (parts.size > 1) parts.dropLast(1).joinToString("/") else relativePath
    }

    fun getDependencies(db: Database, moduleId: Int): List<DependencyEdge> {
        return db.getDependenciesByModule(moduleId)
    }

    fun getDependents(db: Database, moduleId: Int): List<DependencyEdge> {
        return db.getDependentsByModule(moduleId)
    }
}
