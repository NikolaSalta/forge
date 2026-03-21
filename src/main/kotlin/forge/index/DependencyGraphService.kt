package forge.index

import forge.workspace.Database
import forge.workspace.DependencyEdge

/**
 * Builds module/package-level dependency edges from entity relationships.
 * Uses a single SQL JOIN to aggregate import relationships into directory-level
 * dependency edges — avoids N+1 queries that break on large repos (100K+ imports).
 */
class DependencyGraphService {

    fun buildGraph(db: Database): Int {
        db.clearDependencyGraph()

        // Single JOIN query aggregates all imports into (sourceDir, targetPkg, count)
        val edges = db.aggregateDependencyEdges()

        for ((sourcePath, targetPath, count) in edges) {
            db.insertDependencyEdge(
                sourceModuleId = null,
                targetModuleId = null,
                sourcePath = sourcePath,
                targetPath = targetPath,
                depType = "compile",
                weight = count
            )
        }

        return edges.size
    }

    fun getDependencies(db: Database, moduleId: Int): List<DependencyEdge> {
        return db.getDependenciesByModule(moduleId)
    }

    fun getDependents(db: Database, moduleId: Int): List<DependencyEdge> {
        return db.getDependentsByModule(moduleId)
    }
}
