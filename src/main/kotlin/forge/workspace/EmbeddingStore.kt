package forge.workspace

import forge.ForgeConfig
import forge.llm.OllamaClient
import kotlinx.coroutines.runBlocking
import java.nio.ByteBuffer
import java.nio.ByteOrder

// ──────────────────────────────────────────────────────────────
// Public data class
// ──────────────────────────────────────────────────────────────

data class ScoredChunk(
    val chunk: ChunkRecord,
    val score: Float
)

// ──────────────────────────────────────────────────────────────
// EmbeddingStore
// ──────────────────────────────────────────────────────────────

/**
 * Handles embedding generation and semantic similarity search over
 * code/documentation chunks stored in the workspace database.
 *
 * Embeddings are generated via Ollama and persisted as BLOBs in SQLite
 * so they survive across sessions without re-computation.
 *
 * @param client  The Ollama HTTP client used to generate embeddings.
 * @param db      The workspace database that stores chunk rows.
 * @param config  Forge configuration (used to resolve the embedding model name).
 */
class EmbeddingStore(
    private val client: OllamaClient,
    private val db: Database,
    private val config: ForgeConfig
) {

    /** The Ollama model name to use for embeddings. */
    private val embedModel: String = config.models.embed

    // ── Single chunk embedding ───────────────────────────────

    /**
     * Generates an embedding for the chunk identified by [chunkId] using
     * the text provided in [text], then persists the resulting vector in
     * the database.
     *
     * This is a blocking bridge into the coroutine-based Ollama client,
     * suitable for callers that are not themselves running inside a
     * coroutine scope.
     */
    fun embedChunk(chunkId: Int, text: String) {
        val embedding = runBlocking { client.embed(embedModel, text) }
        db.updateChunkEmbedding(chunkId, embedding.toBytes())
    }

    /**
     * Suspend variant of [embedChunk] for callers already in a coroutine.
     */
    suspend fun embedChunkAsync(chunkId: Int, text: String) {
        val embedding = client.embed(embedModel, text)
        db.updateChunkEmbedding(chunkId, embedding.toBytes())
    }

    // ── Batch embedding ──────────────────────────────────────

    /**
     * Iterates over all chunks in the database that do not yet have an
     * embedding and generates one for each, in batches of [batchSize].
     *
     * Returns the total number of chunks that were embedded in this run.
     */
    fun embedAllChunks(batchSize: Int = 10): Int {
        var totalEmbedded = 0
        val failedMarker = ByteArray(0)

        while (true) {
            val batch = db.getChunksWithoutEmbeddings(batchSize)
            if (batch.isEmpty()) break

            for (chunk in batch) {
                try {
                    val content = if (chunk.content.length > maxEmbedChars) {
                        chunk.content.take(maxEmbedChars)
                    } else chunk.content
                    embedChunk(chunk.id, content)
                    totalEmbedded++
                } catch (e: Exception) {
                    System.err.println("Skipping chunk ${chunk.id}: ${e.message}")
                    try { db.updateChunkEmbedding(chunk.id, failedMarker) } catch (_: Exception) {}
                }
            }
        }

        return totalEmbedded
    }

    /**
     * Suspend variant of [embedAllChunks] for callers already in a coroutine.
     */
    suspend fun embedAllChunksAsync(batchSize: Int = 10): Int {
        var totalEmbedded = 0
        val failedMarker = ByteArray(0)

        while (true) {
            val batch = db.getChunksWithoutEmbeddings(batchSize)
            if (batch.isEmpty()) break

            for (chunk in batch) {
                try {
                    val content = if (chunk.content.length > maxEmbedChars) {
                        chunk.content.take(maxEmbedChars)
                    } else chunk.content
                    embedChunkAsync(chunk.id, content)
                    totalEmbedded++
                } catch (e: Exception) {
                    System.err.println("Skipping chunk ${chunk.id}: ${e.message}")
                    try { db.updateChunkEmbedding(chunk.id, failedMarker) } catch (_: Exception) {}
                }
            }
        }

        return totalEmbedded
    }

    // ── Semantic search ──────────────────────────────────────

    /**
     * Finds the most semantically similar chunks to the given [query].
     *
     * 1. Generates an embedding for the query text.
     * 2. Loads every chunk that already has a stored embedding.
     * 3. Computes cosine similarity between the query vector and each
     *    chunk vector.
     * 4. Returns the top [topK] results whose similarity is at or above
     *    [threshold], sorted descending by score.
     */
    fun findSimilar(
        query: String,
        topK: Int = 20,
        threshold: Float = 0.65f
    ): List<ScoredChunk> {
        val queryEmbedding = runBlocking { client.embed(embedModel, query) }
        return rankChunks(queryEmbedding, topK, threshold)
    }

    /**
     * Suspend variant of [findSimilar].
     */
    suspend fun findSimilarAsync(
        query: String,
        topK: Int = 20,
        threshold: Float = 0.65f
    ): List<ScoredChunk> {
        val queryEmbedding = client.embed(embedModel, query)
        return rankChunks(queryEmbedding, topK, threshold)
    }

    // ── Module-scoped search ────────────────────────────────

    /**
     * Finds the most semantically similar chunks to the given [query],
     * but only within chunks belonging to the specified [moduleIds].
     *
     * This is the Stage 2 search used by [forge.retrieval.HierarchicalRetriever]
     * to avoid loading all embeddings into memory at once.
     */
    suspend fun findSimilarInModules(
        query: String,
        moduleIds: List<Int>,
        topK: Int = 20,
        threshold: Float = 0.65f
    ): List<ScoredChunk> {
        if (moduleIds.isEmpty()) return emptyList()

        val queryEmbedding = client.embed(embedModel, query)
        // Cap chunks to prevent OOM/slowness on massive modules
        val allChunks = db.getChunksWithEmbeddingsByModules(moduleIds, limit = config.scale.similaritySearchLimit)

        return allChunks.mapNotNull { chunk ->
            val rawEmbedding = chunk.embedding ?: return@mapNotNull null
            if (rawEmbedding.isEmpty()) return@mapNotNull null  // skip failed-marker chunks
            val chunkEmbedding = rawEmbedding.toFloatArray()
            if (chunkEmbedding.size != queryEmbedding.size) return@mapNotNull null

            val similarity = cosineSimilarity(queryEmbedding, chunkEmbedding)
            if (similarity >= threshold) {
                ScoredChunk(chunk, similarity)
            } else null
        }.sortedByDescending { it.score }
         .take(topK)
    }

    // ── Lazy embedding by ID ─────────────────────────────────

    /**
     * Maximum character length for embedding input. Chunks longer than this are truncated.
     * nomic-embed-text has a BERT context of 2048 tokens. Worst case: binary/escape-sequence
     * data tokenizes at ~1 char/token. Conservative limit ensures we never exceed context.
     */
    private val maxEmbedChars = 1800

    /**
     * Embeds specific chunks identified by their database IDs.
     * Used for lazy (on-demand) embedding of chunks within a module
     * that haven't been embedded yet.
     *
     * If a chunk fails to embed (e.g. too long, model error), a zero-length
     * marker embedding is written so the chunk won't be retried on future calls
     * (avoiding an infinite retry loop).
     */
    suspend fun embedChunksById(chunkIds: List<Int>) {
        val failedMarker = ByteArray(0)  // marker: "attempted but failed"
        for (batch in chunkIds.chunked(config.retrieval.embeddingBatchSize)) {
            for (id in batch) {
                try {
                    val chunk = db.getChunkById(id) ?: continue
                    val content = chunk.content
                    if (content.isBlank()) {
                        db.updateChunkEmbedding(id, failedMarker)
                        continue
                    }
                    // Truncate excessively long chunks to avoid model context overflow
                    val truncated = if (content.length > maxEmbedChars) {
                        content.take(maxEmbedChars)
                    } else content
                    val embedding = client.embed(embedModel, truncated)
                    db.updateChunkEmbedding(id, embedding.toBytes())
                } catch (e: Exception) {
                    // Mark as "attempted" so it won't be retried forever
                    try { db.updateChunkEmbedding(id, failedMarker) } catch (_: Exception) {}
                    System.err.println("Skipping chunk $id (embedding failed): ${e.message}")
                }
            }
        }
    }

    // ── Keyword search ───────────────────────────────────────

    /**
     * Searches chunks using FTS5 full-text search (with LIKE fallback).
     * Delegates to [Database.searchChunksFts].
     */
    fun keywordSearch(query: String, limit: Int = 50): List<ChunkRecord> {
        return db.searchChunksFts(query, limit)
    }

    // ── Internal helpers ─────────────────────────────────────

    private fun rankChunks(
        queryEmbedding: FloatArray,
        topK: Int,
        threshold: Float
    ): List<ScoredChunk> {
        val chunks = db.getChunksWithEmbeddings()

        val scored = chunks.mapNotNull { chunk ->
            val rawEmbedding = chunk.embedding ?: return@mapNotNull null
            if (rawEmbedding.isEmpty()) return@mapNotNull null  // skip failed-marker chunks
            val chunkEmbedding = rawEmbedding.toFloatArray()
            if (chunkEmbedding.size != queryEmbedding.size) return@mapNotNull null

            val similarity = cosineSimilarity(queryEmbedding, chunkEmbedding)
            if (similarity >= threshold) {
                ScoredChunk(chunk, similarity)
            } else {
                null
            }
        }

        return scored
            .sortedByDescending { it.score }
            .take(topK)
    }
}

// ──────────────────────────────────────────────────────────────
// Cosine similarity
// ──────────────────────────────────────────────────────────────

/**
 * Computes the cosine similarity between two vectors.
 * Returns a value in [-1, 1], where 1 means identical direction.
 *
 * If either vector has zero magnitude the result is 0.
 */
fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
    require(a.size == b.size) { "Vectors must have equal length (${a.size} vs ${b.size})" }

    var dot = 0.0f
    var normA = 0.0f
    var normB = 0.0f

    for (i in a.indices) {
        dot += a[i] * b[i]
        normA += a[i] * a[i]
        normB += b[i] * b[i]
    }

    val denominator = Math.sqrt(normA.toDouble()) * Math.sqrt(normB.toDouble())
    return if (denominator == 0.0) 0.0f else (dot / denominator).toFloat()
}

// ──────────────────────────────────────────────────────────────
// FloatArray <-> ByteArray serialization (IEEE 754, little-endian)
// ──────────────────────────────────────────────────────────────

/**
 * Serializes a [FloatArray] into a [ByteArray] suitable for storage in a
 * SQLite BLOB column.  Each float is written as 4 bytes in little-endian
 * order, producing a byte array of length `size * 4`.
 */
fun FloatArray.toBytes(): ByteArray {
    val buffer = ByteBuffer.allocate(size * Float.SIZE_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN)
    for (value in this) {
        buffer.putFloat(value)
    }
    return buffer.array()
}

/**
 * Deserializes a [ByteArray] (produced by [FloatArray.toBytes]) back into
 * a [FloatArray].  Expects the byte array length to be a multiple of 4.
 */
fun ByteArray.toFloatArray(): FloatArray {
    require(size % Float.SIZE_BYTES == 0) {
        "ByteArray length ($size) is not a multiple of ${Float.SIZE_BYTES}"
    }
    val buffer = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
    return FloatArray(size / Float.SIZE_BYTES) { buffer.getFloat() }
}
