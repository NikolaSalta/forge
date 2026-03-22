package forge.retrieval

import forge.ForgeConfig
import forge.llm.OllamaClient
import forge.workspace.Database
import forge.workspace.EmbeddingStore
import forge.workspace.ModuleRecord
import forge.workspace.ScoredChunk

/**
 * Relevance score for a module.
 */
data class ModuleRelevance(
    val module: ModuleRecord,
    val score: Float,
    val reason: String
)

/**
 * Combined result from hierarchical retrieval.
 */
data class RetrievalResult(
    val relevantModules: List<ModuleRelevance>,
    val chunks: List<ScoredChunk>,
    val searchStrategy: String
)

/**
 * Two-stage hierarchical retriever for massive codebases.
 *
 * Stage 1: Module-level search — identify which project modules are relevant
 * Stage 2: Chunk-level search — find specific code within those modules
 *
 * This avoids loading all embeddings into memory (which would cause OOM
 * on very large projects with 100K+ files).
 */
class HierarchicalRetriever(
    private val config: ForgeConfig,
    private val ollama: OllamaClient,
    private val db: Database
) {
    private val embeddingStore = EmbeddingStore(ollama, db, config)

    /**
     * Stage 1: Find relevant modules using keyword matching + module summaries.
     */
    suspend fun findRelevantModules(query: String, topK: Int = 10): List<ModuleRelevance> {
        val allModules = db.getAllModules()
        if (allModules.isEmpty()) return emptyList()

        val queryTerms = query.lowercase().split(Regex("\\s+"))
            .filter { it.length > 2 }

        val scored = allModules.map { module ->
            val keywordScore = calculateKeywordScore(module, queryTerms)
            val summaryScore = if (module.summary != null) {
                calculateSummaryScore(module.summary, query)
            } else 0f
            val score = maxOf(keywordScore, summaryScore)
            ModuleRelevance(module, score, buildReason(module, keywordScore, summaryScore))
        }

        return scored
            .filter { it.score > 0.05f }
            .sortedByDescending { it.score }
            .take(topK)
    }

    /**
     * Stage 2: Find relevant chunks within specific modules.
     * Ensures module embeddings exist before searching.
     */
    suspend fun findRelevantChunksInModules(
        query: String,
        modules: List<ModuleRecord>,
        topK: Int = 20,
        threshold: Float = config.retrieval.similarityThreshold
    ): List<ScoredChunk> {
        val moduleIds = modules.map { it.id }

        // Ensure embeddings exist for these modules (lazy embedding)
        for (module in modules) {
            val unembedded = db.getChunksWithoutEmbeddingsByModule(module.id, config.scale.moduleEmbeddingBudget)
            if (unembedded.isNotEmpty()) {
                embeddingStore.embedChunksById(unembedded.map { it.id })
            }
        }

        // Search within module-scoped embeddings
        return embeddingStore.findSimilarInModules(query, moduleIds, topK, threshold)
    }

    /**
     * Combined two-stage search.
     */
    suspend fun search(
        query: String,
        moduleTopK: Int = config.scale.moduleTopK,
        chunkTopK: Int = 20,
        threshold: Float = config.retrieval.similarityThreshold
    ): RetrievalResult {
        // Stage 1
        val relevantModules = findRelevantModules(query, moduleTopK)

        if (relevantModules.isEmpty()) {
            // Fallback to global search if no modules found
            return RetrievalResult(
                relevantModules = emptyList(),
                chunks = emptyList(),
                searchStrategy = "no_modules_found"
            )
        }

        // Stage 2
        val chunks = findRelevantChunksInModules(
            query, relevantModules.map { it.module }, chunkTopK, threshold
        )

        return RetrievalResult(
            relevantModules = relevantModules,
            chunks = chunks,
            searchStrategy = "hierarchical"
        )
    }

    /**
     * Focus mode: search only within a specific module by name.
     */
    suspend fun searchInModule(
        query: String,
        moduleName: String,
        topK: Int = 20,
        threshold: Float = config.retrieval.similarityThreshold
    ): List<ScoredChunk> {
        val module = db.getModuleByName(moduleName) ?: return emptyList()
        return findRelevantChunksInModules(query, listOf(module), topK, threshold)
    }

    // ── Internal helpers ─────────────────────────────────────

    private fun calculateKeywordScore(module: ModuleRecord, queryTerms: List<String>): Float {
        val moduleName = module.name.lowercase()
        val modulePath = module.path.lowercase()
        val moduleType = (module.moduleType ?: "").lowercase()
        val deps = (module.dependencies ?: "").lowercase()

        var matchCount = 0
        for (term in queryTerms) {
            if (moduleName.contains(term)) matchCount += 3
            if (modulePath.contains(term)) matchCount += 2
            if (moduleType.contains(term)) matchCount += 1
            if (deps.contains(term)) matchCount += 1
        }

        return if (queryTerms.isNotEmpty()) {
            (matchCount.toFloat() / (queryTerms.size * 3)).coerceIn(0f, 1f)
        } else 0f
    }

    private fun calculateSummaryScore(summary: String, query: String): Float {
        // Simple term overlap for now; could use embeddings for better accuracy
        val summaryTerms = summary.lowercase().split(Regex("\\s+")).toSet()
        val queryTerms = query.lowercase().split(Regex("\\s+")).filter { it.length > 2 }
        if (queryTerms.isEmpty()) return 0f

        val overlap = queryTerms.count { it in summaryTerms }
        return (overlap.toFloat() / queryTerms.size).coerceIn(0f, 1f)
    }

    private fun buildReason(module: ModuleRecord, keywordScore: Float, summaryScore: Float): String {
        return when {
            keywordScore > summaryScore -> "name/path match (${String.format("%.2f", keywordScore)})"
            summaryScore > 0 -> "summary match (${String.format("%.2f", summaryScore)})"
            else -> "low relevance"
        }
    }
}
