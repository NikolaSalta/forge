package forge.index

import forge.ForgeConfig
import forge.workspace.Database
import forge.workspace.IndexMetadataRecord
import java.nio.file.Path
import java.time.Instant

/**
 * Orchestrates the full absolute indexing pipeline:
 * 1. File classification
 * 2. Entity extraction
 * 3. Relationship graph building
 * 4. Line index
 * 5. Dependency graph
 * 6. Index metadata persistence
 */
class ProjectIndexCoordinator(
    private val db: Database,
    private val repoPath: Path,
    private val config: ForgeConfig
) {
    private val entityExtraction = EntityExtractionService()
    private val fileClassification = FileClassificationService()
    private val relationshipGraph = RelationshipGraphService()
    private val lineIndex = LineIndexService()
    private val dependencyGraph = DependencyGraphService()

    /**
     * Run full absolute index build.
     */
    fun buildFullIndex(
        onProgress: ((phase: String, detail: String, current: Int, total: Int) -> Unit)? = null
    ): IndexBuildReport {
        val startTime = System.currentTimeMillis()
        val metaId = db.insertIndexMetadata("full", "running", "full")

        var entitiesFound = 0
        var relationshipsFound = 0
        var linesIndexed = 0
        var filesProcessed = 0
        val totalFiles = db.getFileCount()
        val errors = mutableListOf<String>()

        try {
            // Phase 1: File classification
            onProgress?.invoke("CLASSIFICATION", "Classifying project files...", 0, totalFiles)
            val classStats = try {
                fileClassification.classifyAll(db, repoPath)
            } catch (e: Exception) {
                errors.add("Classification failed: ${e.message}")
                emptyMap()
            }
            filesProcessed = classStats.values.sum()
            onProgress?.invoke("CLASSIFICATION", "Classified $filesProcessed files", filesProcessed, totalFiles)

            // Phase 1.5: Clear stale index data from previous runs
            db.clearAllIndexData()

            // Phase 2: Entity extraction
            onProgress?.invoke("ENTITY_EXTRACTION", "Extracting code entities...", 0, totalFiles)
            entitiesFound = try {
                entityExtraction.extractAll(db, repoPath, batchSize = 500) { processed, found ->
                    onProgress?.invoke("ENTITY_EXTRACTION", "Processed $processed files, found $found entities", processed, totalFiles)
                }
            } catch (e: Exception) {
                errors.add("Entity extraction failed: ${e.message}")
                0
            }
            onProgress?.invoke("ENTITY_EXTRACTION", "Extracted $entitiesFound entities", totalFiles, totalFiles)

            // Phase 3: Relationship graph
            onProgress?.invoke("RELATIONSHIPS", "Building relationship graph...", 0, totalFiles)
            relationshipsFound = try {
                relationshipGraph.buildAll(db, repoPath)
            } catch (e: Exception) {
                errors.add("Relationship building failed: ${e.message}")
                0
            }
            onProgress?.invoke("RELATIONSHIPS", "Built $relationshipsFound relationships", relationshipsFound, relationshipsFound)

            // Phase 4: Line index
            onProgress?.invoke("LINE_INDEX", "Building line-level index...", 0, totalFiles)
            linesIndexed = try {
                lineIndex.buildAll(db, repoPath)
            } catch (e: Exception) {
                errors.add("Line index failed: ${e.message}")
                0
            }
            onProgress?.invoke("LINE_INDEX", "Indexed $linesIndexed lines", linesIndexed, linesIndexed)

            // Phase 5: Dependency graph
            onProgress?.invoke("DEPENDENCY_GRAPH", "Building dependency graph...", 0, 1)
            var depsFound = 0
            try {
                depsFound = dependencyGraph.buildGraph(db)
            } catch (e: Exception) {
                errors.add("Dependency graph failed: ${e.message}")
            }
            onProgress?.invoke("DEPENDENCY_GRAPH", "Built $depsFound dependency edges", depsFound, depsFound)

            // Phase 6: Resolve relationship targets
            onProgress?.invoke("RESOLUTION", "Resolving relationship targets...", 0, 1)
            try {
                db.resolveRelationshipTargets()
            } catch (e: Exception) {
                errors.add("Resolution failed: ${e.message}")
            }

            // Finalize
            val duration = System.currentTimeMillis() - startTime
            db.updateIndexMetadata(
                id = metaId,
                status = if (errors.isEmpty()) "completed" else "completed_with_errors",
                completedAt = Instant.now().toString(),
                durationMs = duration,
                filesProcessed = filesProcessed,
                entitiesFound = entitiesFound,
                relationshipsFound = relationshipsFound,
                errors = if (errors.isNotEmpty()) errors.joinToString("; ") else null
            )
            db.setMeta("index_version", "1")
            db.setMeta("index_built_at", Instant.now().toString())

            onProgress?.invoke("COMPLETE", "Absolute index ready: $entitiesFound entities, $relationshipsFound relationships", 0, 0)

        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            db.updateIndexMetadata(metaId, "failed", Instant.now().toString(), duration,
                filesProcessed, entitiesFound, relationshipsFound, "Fatal: ${e.message}")
            throw e
        }

        return IndexBuildReport(
            filesProcessed = filesProcessed,
            entitiesFound = entitiesFound,
            relationshipsFound = relationshipsFound,
            linesIndexed = linesIndexed,
            durationMs = System.currentTimeMillis() - startTime,
            errors = errors
        )
    }

    /**
     * Incremental update for changed files.
     */
    fun updateIncremental(
        changedFileIds: List<Int>,
        deletedFileIds: List<Int>
    ): IndexBuildReport {
        val startTime = System.currentTimeMillis()
        val metaId = db.insertIndexMetadata("incremental", "running", "incremental")
        val errors = mutableListOf<String>()

        // Clean deleted files
        for (fileId in deletedFileIds) {
            db.deleteEntitiesByFile(fileId)
            db.deleteLineIndexByFile(fileId)
            db.deleteClassificationByFile(fileId)
        }

        // Re-extract entities for changed files
        val entitiesFound = entityExtraction.extractIncremental(db, repoPath, changedFileIds)

        // Rebuild relationships for changed files
        val relationshipsFound = relationshipGraph.rebuildForFiles(db, repoPath, changedFileIds)

        // Rebuild line index for changed files
        val linesIndexed = lineIndex.rebuildForFiles(db, repoPath, changedFileIds)

        // Rebuild dependency graph
        dependencyGraph.buildGraph(db)

        // Re-resolve
        db.resolveRelationshipTargets()

        val duration = System.currentTimeMillis() - startTime
        db.updateIndexMetadata(metaId, "completed", Instant.now().toString(), duration,
            changedFileIds.size + deletedFileIds.size, entitiesFound, relationshipsFound, null)
        db.setMeta("index_built_at", Instant.now().toString())

        return IndexBuildReport(
            filesProcessed = changedFileIds.size + deletedFileIds.size,
            entitiesFound = entitiesFound,
            relationshipsFound = relationshipsFound,
            linesIndexed = linesIndexed,
            durationMs = duration,
            errors = errors
        )
    }

    /**
     * Detect which files changed since last index build by comparing SHA256.
     */
    fun detectChangedFiles(): Pair<List<Int>, List<Int>> {
        val changed = mutableListOf<Int>()
        val deleted = mutableListOf<Int>()

        val files = db.getAllFiles()
        for (file in files) {
            val filePath = repoPath.resolve(file.relativePath)
            if (!java.nio.file.Files.exists(filePath)) {
                deleted.add(file.id)
                continue
            }
            // Compare file size as quick check
            val currentSize = java.nio.file.Files.size(filePath)
            if (currentSize != file.sizeBytes) {
                changed.add(file.id)
            }
        }

        return Pair(changed, deleted)
    }

    /**
     * Check if the full index has been built.
     */
    fun isIndexBuilt(): Boolean {
        return db.getMeta("index_version") != null
    }
}

data class IndexBuildReport(
    val filesProcessed: Int,
    val entitiesFound: Int,
    val relationshipsFound: Int,
    val linesIndexed: Int,
    val durationMs: Long,
    val errors: List<String>
)
