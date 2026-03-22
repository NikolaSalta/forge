package forge.core

import forge.ForgeConfig
import forge.index.IndexQueryService
import forge.llm.ChatMessage
import forge.llm.OllamaClient
import forge.workspace.Database
import java.nio.file.Path

/**
 * Specialized pipeline for REPO_PLATFORM_TRANSFORMATION tasks.
 *
 * Instead of DeepAnalyzer's per-module analysis, this pipeline:
 * 1. Clusters the repo using index data
 * 2. Compresses clusters into a synthesis-ready summary
 * 3. Runs final synthesis with a strong model (gpt-oss:20b)
 * 4. Validates grounding against real repo facts
 */
class TransformationPipeline(
    private val config: ForgeConfig,
    private val ollama: OllamaClient,
    private val db: Database,
    private val repoPath: Path
) {
    /**
     * Builds a repo identity block from index data.
     * This anchors the LLM so it cannot hallucinate repo contents.
     */
    fun buildRepoIdentity(): String {
        val queryService = IndexQueryService(db)
        val stats = queryService.getIndexStats()
        val filesByClass = stats.filesByClassification

        // Get top-level directories
        val topDirs = try {
            java.nio.file.Files.list(repoPath).use { stream ->
                stream.filter { java.nio.file.Files.isDirectory(it) }
                    .map { it.fileName.toString() }
                    .filter { !it.startsWith(".") }
                    .sorted()
                    .toList()
            }
        } catch (_: Exception) { emptyList() }

        // Get top-level files
        val topFiles = try {
            java.nio.file.Files.list(repoPath).use { stream ->
                stream.filter { java.nio.file.Files.isRegularFile(it) }
                    .map { it.fileName.toString() }
                    .sorted()
                    .toList()
            }
        } catch (_: Exception) { emptyList() }

        // Get entity type summary
        val entitySummary = stats.entitiesByType
            .filter { it.value > 0 }
            .entries.sortedByDescending { it.value }
            .joinToString(", ") { "${it.key}: ${it.value}" }

        // Get top classes by name (real classes from index)
        val topClasses = db.searchEntities("", limit = 100)
            .filter { it.entityType == "class" }
            .take(30)
            .joinToString("\n") { "  - ${it.name} (${it.filePath}:${it.startLine})" }

        // Get modules if discovered
        val modules = db.getAllModules()
        val moduleList = if (modules.isNotEmpty()) {
            modules.joinToString("\n") { "  - ${it.name} (${it.path}, type=${it.moduleType})" }
        } else "  (no modules discovered)"

        return """
## REPO IDENTITY — USE ONLY THESE FACTS
Repository path: $repoPath
Repository name: ${repoPath.fileName}
Top-level directories: ${topDirs.joinToString(", ")}
Top-level files: ${topFiles.joinToString(", ")}
Total indexed files: ${stats.totalEntities} entities across ${filesByClass.values.sum()} files
File classification: ${filesByClass.entries.joinToString(", ") { "${it.key}: ${it.value}" }}
Entity summary: $entitySummary
Discovered modules:
$moduleList
Key classes found in index:
$topClasses

CRITICAL RULES:
- This repository IS what the index says it is. Do NOT invent modules, frameworks, or technologies not listed above.
- If the index shows classes like "Article", "User", "Role" — those exist. If it shows "BesselJ0", "DeviceSpec" — those exist.
- Do NOT claim this repo uses TensorFlow, Kubernetes, Docker, PostgreSQL, gRPC unless the top-level files/dirs prove it.
- Base your entire answer on these REAL facts.
""".trimIndent()
    }

    /**
     * Stage 1: Repository Clustering
     * Groups repo contents by category using index data — no LLM needed.
     */
    fun buildClustering(): String {
        val modules = db.getAllModules()
        val filesByClass = IndexQueryService(db).getIndexStats().filesByClassification

        // Group entities by top-level directory
        val entityGroups = mutableMapOf<String, MutableList<String>>()
        val allEntities = db.searchEntities("", limit = 500)
            .filter { it.entityType in listOf("class", "interface", "enum") }

        for (entity in allEntities) {
            val topDir = entity.filePath?.split("/")?.firstOrNull() ?: "root"
            entityGroups.getOrPut(topDir) { mutableListOf() }.add(
                "${entity.entityType} ${entity.name}"
            )
        }

        return buildString {
            appendLine("## Repository Clustering Result")
            appendLine()
            appendLine("### Top-Level Project Groups")
            for ((dir, entities) in entityGroups.entries.sortedByDescending { it.value.size }) {
                appendLine("#### $dir (${entities.size} entities)")
                for (e in entities.take(15)) {
                    appendLine("  - $e")
                }
                if (entities.size > 15) appendLine("  - ... and ${entities.size - 15} more")
            }
            appendLine()
            appendLine("### File Classification")
            for ((cls, count) in filesByClass) {
                appendLine("- $cls: $count files")
            }
            appendLine()
            appendLine("### Modules")
            for (m in modules) {
                appendLine("- ${m.name} (${m.path}, type=${m.moduleType})")
            }
        }
    }

    /**
     * Stage 2: Clustering Compression
     * Compresses clustering into a concise synthesis-ready summary.
     * Uses a lightweight LLM call to compress (not the heavy synth model).
     */
    suspend fun compressClustering(clustering: String): String {
        val systemPrompt = """You are a repository clustering compressor.
Your ONLY job is to compress the clustering result into a concise summary for the final architect.
Remove all per-file/per-class detail. Keep ONLY:
1. Repository shape (how many groups, what types)
2. Major clusters (3-5 bullet points)
3. Reusable vs example-only parts
4. Candidate future platform modules
5. Repeated patterns
6. Major risks
Output must be under 800 words. No code examples. No file paths unless critical."""

        val messages = listOf(
            ChatMessage("system", systemPrompt),
            ChatMessage("user", "Compress this clustering result:\n\n$clustering")
        )

        return try {
            ollama.chat(config.models.classify, messages)
        } catch (_: Exception) {
            // If compression fails, use raw clustering truncated
            clustering.take(3000)
        }
    }

    /**
     * Stage 3: Final Synthesis
     * Produces the actual transformation roadmap using the heavy model.
     */
    suspend fun synthesize(
        userQuery: String,
        repoIdentity: String,
        compressedClustering: String
    ): String {
        val systemPrompt = """You are a Principal Software Architect.
You are given a repository clustering summary and a user's transformation request.
Your task is to produce a REPOSITORY-WIDE PLATFORM TRANSFORMATION ROADMAP.

$repoIdentity

FORBIDDEN — DO NOT:
- Describe individual modules in detail
- Produce per-file/per-class commentary
- Return a generic architecture template
- Claim the repo uses frameworks/tools not proven by the identity block above
- Return a "Repository Analysis Report" — that is the WRONG answer shape

REQUIRED — YOU MUST:
- Address the whole repository as a single transformation target
- Classify which parts become platform modules vs examples vs shared libraries
- Design the actual target architecture based on REAL repo contents
- Produce a migration roadmap with concrete phases

OUTPUT FORMAT (follow exactly):
1. What the Repository Should Become
2. Target Modular Architecture
3. Domain Separation
4. API Layer Design
5. Persistence Layer Design
6. Validation Strategy
7. Logging and Observability Strategy
8. Security Rules
9. Integration Contracts
10. Testing Strategy
11. Step-by-Step Migration Plan
12. Final Practical Recommendations"""

        val userMessage = """User's request: $userQuery

Repository clustering summary:
$compressedClustering

Based on the REAL repository structure shown above, produce the transformation roadmap."""

        val messages = listOf(
            ChatMessage("system", systemPrompt),
            ChatMessage("user", userMessage)
        )

        // Use SYNTHESIZE model (gpt-oss:20b) with fallback
        val model = try {
            val synthModel = config.models.synthesize
            // Check if model is available via Ollama
            val loaded = ollama.getLoadedModels()
            if (loaded.none { it.name.contains(synthModel.split(":").first()) }) {
                // Try to preload the synthesis model
                ollama.preloadModel(synthModel)
            }
            synthModel
        } catch (_: Exception) {
            config.models.fallback.synthesize
        }

        return ollama.chat(model, messages)
    }

    /**
     * Stage 4: Grounding Validation
     * Checks that the synthesis doesn't contain fabricated claims.
     */
    fun validateGrounding(response: String, repoIdentity: String): GroundingResult {
        val warnings = mutableListOf<String>()

        // Extract top-level dirs from identity for grounding check
        val identityLower = repoIdentity.lowercase()

        // Check for common hallucination patterns
        val suspiciousTerms = listOf(
            "kubernetes" to "Kubernetes",
            "helm" to "Helm charts",
            "docker-compose" to "Docker Compose",
            "postgresql" to "PostgreSQL",
            "github actions" to "GitHub Actions",
            "circleci" to "CircleCI",
            "jenkins" to "Jenkins",
            "grpc" to "gRPC",
            "kafka" to "Kafka",
            "rabbitmq" to "RabbitMQ",
            "redis" to "Redis",
            "elasticsearch" to "Elasticsearch"
        )

        val responseLower = response.lowercase()
        for ((term, label) in suspiciousTerms) {
            if (term in responseLower && term !in identityLower) {
                warnings.add("Response mentions $label but repo identity does not support this")
            }
        }

        return GroundingResult(
            isGrounded = warnings.size <= 2,
            warnings = warnings,
            response = response
        )
    }

    /**
     * Run the full transformation pipeline: cluster → compress → synthesize → validate
     */
    suspend fun execute(
        userQuery: String,
        onProgress: (stage: String, detail: String) -> Unit
    ): TransformationResult {
        onProgress("REPO_IDENTITY", "Building repository identity from index...")
        val repoIdentity = buildRepoIdentity()

        onProgress("CLUSTERING", "Clustering repository structure...")
        val clustering = buildClustering()

        onProgress("COMPRESSION", "Compressing clusters for synthesis...")
        val compressed = compressClustering(clustering)

        onProgress("SYNTHESIS", "Generating transformation roadmap with ${config.models.synthesize}...")
        val synthesis = synthesize(userQuery, repoIdentity, compressed)

        onProgress("GROUNDING", "Validating factual grounding...")
        val grounding = validateGrounding(synthesis, repoIdentity)

        val finalResponse = if (grounding.isGrounded) {
            synthesis
        } else {
            val warningBlock = grounding.warnings.joinToString("\n") { "⚠️ $it" }
            "⚠️ **Grounding warnings:**\n$warningBlock\n\n---\n\n$synthesis"
        }

        return TransformationResult(
            response = finalResponse,
            repoIdentity = repoIdentity,
            clustering = clustering,
            compressedClustering = compressed,
            groundingResult = grounding
        )
    }
}

data class GroundingResult(
    val isGrounded: Boolean,
    val warnings: List<String>,
    val response: String
)

data class TransformationResult(
    val response: String,
    val repoIdentity: String,
    val clustering: String,
    val compressedClustering: String,
    val groundingResult: GroundingResult
)
