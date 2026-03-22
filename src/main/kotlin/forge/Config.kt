package forge

import org.yaml.snakeyaml.Yaml
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

data class OllamaConfig(
    val host: String = "http://127.0.0.1:11434",
    val timeoutSeconds: Int = 300,
    val retryCount: Int = 3
)

data class ModelsConfig(
    val classify: String = "qwen3:1.7b",
    val reason: String = "qwen2.5:14b",
    val code: String = "qwen2.5-coder:7b",
    val embed: String = "qwen3-embedding:0.6b",
    val summarize: String = "ministral-3:3b",
    val vision: String = "qwen3-vl:2b",
    val fallback: FallbackModels = FallbackModels()
)

data class FallbackModels(
    val classify: String = "deepseek-r1:1.5b",
    val reason: String = "qwen2.5:14b",
    val code: String = "qwen2.5-coder:7b",
    val summarize: String = "qwen3:1.7b"
)

data class WorkspaceConfig(
    val baseDir: String = "~/.forge/workspaces",
    val maxFileSizeKb: Int = 2_048_000,
    val chunkMaxLines: Int = 200_000,
    val chunkOverlapLines: Int = 10,
    val maxChunksPerFile: Int = 200_000
)

data class RetrievalConfig(
    val maxContextChunks: Int = 150,
    val similarityThreshold: Float = 0.25f,
    val embeddingBatchSize: Int = 10,
    val scanIgnore: List<String> = listOf(
        "node_modules", ".git", "__pycache__", "build", "dist",
        ".idea", ".vscode", "target", "vendor", ".gradle", "bin", "obj"
    )
)

data class VoiceInputConfig(
    val whisperModel: String = "base",
    val whisperModelDir: String = "~/.forge/models/whisper",
    val language: String = "auto",
    val maxRecordingSec: Int = 30,
    val silenceThresholdDb: Double = -40.0,
    val silenceDurationMs: Int = 1500
)

data class UiConfig(
    val showTrace: Boolean = true,
    val showEvidence: Boolean = true,
    val streaming: Boolean = true
)

data class SatelliteRepo(
    val name: String,
    val url: String,
    val localPath: String? = null,
    val branch: String = "master"
)

data class MultiRepoConfig(
    val satellites: List<SatelliteRepo> = emptyList(),
    val autoClone: Boolean = false,
    val shallowClone: Boolean = true,
    val cloneBaseDir: String = "~/.forge/repos"
)

data class ScaleConfig(
    val parallelScanThreads: Int = Runtime.getRuntime().availableProcessors(),
    val scanBatchSize: Int = 500,
    val incrementalScan: Boolean = true,
    val fts5Enabled: Boolean = true,
    val moduleEmbeddingBudget: Int = 8_000_000,
    val moduleTopK: Int = 20,
    val similaritySearchLimit: Int = 50_000_000,
    val tokenBudget: Int = 128_000_000,
    val moduleSummaryCache: Boolean = true
)

data class DecompositionConfig(
    val enabled: Boolean = true,
    val maxPartitions: Int = 32_000,
    val maxParallelLlmCalls: Int = 8_000,
    val synthesisEnabled: Boolean = true,
    val heuristicOnly: Boolean = false,
    val sessionTracking: Boolean = true
)

data class EvolutionConfig(
    val enabled: Boolean = true,
    val collectTrainingData: Boolean = true,
    val autoValidateOnSuccess: Boolean = true,
    val qualityThreshold: Double = 0.6,
    val productModel: String? = null,
    val modelExportDir: String = "~/.forge/models/exports",
    val datasetFormat: String = "jsonl",
    val piiFilterEnabled: Boolean = true
)

data class AgentOrchestrationConfig(
    val enabled: Boolean = true,
    val alwaysHot: List<String> = listOf("qwen3:1.7b", "qwen2.5:14b"),
    val specialistModels: Map<String, String> = mapOf(
        "CODE" to "qwen2.5-coder:14b",
        "EMBED" to "qwen3-embedding:0.6b",
        "SUMMARIZE" to "qwen2.5:14b",
        "VISION" to "qwen2.5vl:7b"
    ),
    val preloadOnStartup: Boolean = true,
    val maxTotalMemoryGb: Double = 12.0,
    val heavyModelThresholdGb: Double = 6.0,
    val keepAliveMinutes: Int = 10
)

data class ForgeConfig(
    val ollama: OllamaConfig = OllamaConfig(),
    val models: ModelsConfig = ModelsConfig(),
    val workspace: WorkspaceConfig = WorkspaceConfig(),
    val retrieval: RetrievalConfig = RetrievalConfig(),
    val voice: VoiceInputConfig = VoiceInputConfig(),
    val ui: UiConfig = UiConfig(),
    val multiRepo: MultiRepoConfig = MultiRepoConfig(),
    val scale: ScaleConfig = ScaleConfig(),
    val decomposition: DecompositionConfig = DecompositionConfig(),
    val evolution: EvolutionConfig = EvolutionConfig(),
    val agents: AgentOrchestrationConfig = AgentOrchestrationConfig()
) {
    fun resolvedWorkspaceDir(): Path {
        val dir = workspace.baseDir.replaceFirst("~", System.getProperty("user.home"))
        return Paths.get(dir)
    }

    fun resolvedWhisperModelDir(): Path {
        val dir = voice.whisperModelDir.replaceFirst("~", System.getProperty("user.home"))
        return Paths.get(dir)
    }

    fun resolvedCloneBaseDir(): Path {
        val dir = multiRepo.cloneBaseDir.replaceFirst("~", System.getProperty("user.home"))
        return Paths.get(dir)
    }

    fun resolvedModelExportDir(): Path {
        val dir = evolution.modelExportDir.replaceFirst("~", System.getProperty("user.home"))
        return Paths.get(dir)
    }

    companion object {
        fun load(configPath: Path? = null): ForgeConfig {
            // Try user config, then default
            val yaml = Yaml()

            // 1. Load defaults from resources
            val defaults = loadDefaults(yaml)

            // 2. Try user config file
            if (configPath != null && Files.exists(configPath)) {
                return mergeConfig(defaults, loadFromFile(yaml, configPath))
            }

            // 3. Try ~/.forge/forge.yaml
            val userConfig = Paths.get(System.getProperty("user.home"), ".forge", "forge.yaml")
            if (Files.exists(userConfig)) {
                return mergeConfig(defaults, loadFromFile(yaml, userConfig))
            }

            return defaults
        }

        private fun loadDefaults(yaml: Yaml): ForgeConfig {
            val stream: InputStream? = ForgeConfig::class.java.classLoader
                .getResourceAsStream("forge-default.yaml")
            if (stream != null) {
                return parseYaml(yaml, stream)
            }
            return ForgeConfig()
        }

        private fun loadFromFile(yaml: Yaml, path: Path): ForgeConfig {
            return parseYaml(yaml, Files.newInputStream(path))
        }

        @Suppress("UNCHECKED_CAST")
        private fun parseYaml(yaml: Yaml, stream: InputStream): ForgeConfig {
            val data = yaml.load<Map<String, Any>>(stream) ?: return ForgeConfig()

            val ollamaMap = data["ollama"] as? Map<String, Any> ?: emptyMap()
            val modelsMap = data["models"] as? Map<String, Any> ?: emptyMap()
            val fallbackMap = modelsMap["fallback"] as? Map<String, Any> ?: emptyMap()
            val workspaceMap = data["workspace"] as? Map<String, Any> ?: emptyMap()
            val retrievalMap = data["retrieval"] as? Map<String, Any> ?: emptyMap()
            val voiceMap = data["voice"] as? Map<String, Any> ?: emptyMap()
            val uiMap = data["ui"] as? Map<String, Any> ?: emptyMap()
            val multiRepoMap = data["multi_repo"] as? Map<String, Any> ?: emptyMap()
            val scaleMap = data["scale"] as? Map<String, Any> ?: emptyMap()
            val decompMap = data["decomposition"] as? Map<String, Any> ?: emptyMap()
            val evolutionMap = data["evolution"] as? Map<String, Any> ?: emptyMap()
            val agentsMap = data["agents"] as? Map<String, Any> ?: emptyMap()
            val satellitesList = (multiRepoMap["satellites"] as? List<*>)?.mapNotNull { item ->
                val m = item as? Map<*, *> ?: return@mapNotNull null
                SatelliteRepo(
                    name = m["name"] as? String ?: return@mapNotNull null,
                    url = m["url"] as? String ?: return@mapNotNull null,
                    localPath = m["local_path"] as? String,
                    branch = m["branch"] as? String ?: "master"
                )
            } ?: emptyList()

            return ForgeConfig(
                ollama = OllamaConfig(
                    host = ollamaMap["host"] as? String ?: "http://127.0.0.1:11434",
                    timeoutSeconds = (ollamaMap["timeout_seconds"] as? Number)?.toInt() ?: 120,
                    retryCount = (ollamaMap["retry_count"] as? Number)?.toInt() ?: 1
                ),
                models = ModelsConfig(
                    classify = modelsMap["classify"] as? String ?: "qwen3:1.7b",
                    reason = modelsMap["reason"] as? String ?: "qwen2.5:14b",
                    code = modelsMap["code"] as? String ?: "qwen2.5-coder:7b",
                    embed = modelsMap["embed"] as? String ?: "qwen3-embedding:0.6b",
                    summarize = modelsMap["summarize"] as? String ?: "ministral-3:3b",
                    vision = modelsMap["vision"] as? String ?: "qwen3-vl:2b",
                    fallback = FallbackModels(
                        classify = fallbackMap["classify"] as? String ?: "deepseek-r1:1.5b",
                        reason = fallbackMap["reason"] as? String ?: "qwen2.5:14b",
                        code = fallbackMap["code"] as? String ?: "qwen2.5-coder:7b",
                        summarize = fallbackMap["summarize"] as? String ?: "qwen3:1.7b"
                    )
                ),
                workspace = WorkspaceConfig(
                    baseDir = workspaceMap["base_dir"] as? String ?: "~/.forge/workspaces",
                    maxFileSizeKb = (workspaceMap["max_file_size_kb"] as? Number)?.toInt() ?: 2_048_000,
                    chunkMaxLines = (workspaceMap["chunk_max_lines"] as? Number)?.toInt() ?: 200_000,
                    chunkOverlapLines = (workspaceMap["chunk_overlap_lines"] as? Number)?.toInt() ?: 10,
                    maxChunksPerFile = (workspaceMap["max_chunks_per_file"] as? Number)?.toInt() ?: 200_000
                ),
                retrieval = RetrievalConfig(
                    maxContextChunks = (retrievalMap["max_context_chunks"] as? Number)?.toInt() ?: 150,
                    similarityThreshold = (retrievalMap["similarity_threshold"] as? Number)?.toFloat() ?: 0.25f,
                    embeddingBatchSize = (retrievalMap["embedding_batch_size"] as? Number)?.toInt() ?: 10,
                    scanIgnore = (retrievalMap["scan_ignore"] as? List<*>)?.map { it.toString() }
                        ?: RetrievalConfig().scanIgnore
                ),
                voice = VoiceInputConfig(
                    whisperModel = voiceMap["whisper_model"] as? String ?: "base",
                    whisperModelDir = voiceMap["whisper_model_dir"] as? String ?: "~/.forge/models/whisper",
                    language = voiceMap["language"] as? String ?: "auto",
                    maxRecordingSec = (voiceMap["max_recording_sec"] as? Number)?.toInt() ?: 30,
                    silenceThresholdDb = (voiceMap["silence_threshold_db"] as? Number)?.toDouble() ?: -40.0,
                    silenceDurationMs = (voiceMap["silence_duration_ms"] as? Number)?.toInt() ?: 1500
                ),
                ui = UiConfig(
                    showTrace = uiMap["show_trace"] as? Boolean ?: true,
                    showEvidence = uiMap["show_evidence"] as? Boolean ?: true,
                    streaming = uiMap["streaming"] as? Boolean ?: true
                ),
                multiRepo = MultiRepoConfig(
                    satellites = satellitesList,
                    autoClone = multiRepoMap["auto_clone"] as? Boolean ?: false,
                    shallowClone = multiRepoMap["shallow_clone"] as? Boolean ?: true,
                    cloneBaseDir = multiRepoMap["clone_base_dir"] as? String ?: "~/.forge/repos"
                ),
                scale = ScaleConfig(
                    parallelScanThreads = (scaleMap["parallel_scan_threads"] as? Number)?.toInt()
                        ?: Runtime.getRuntime().availableProcessors(),
                    scanBatchSize = (scaleMap["scan_batch_size"] as? Number)?.toInt() ?: 500,
                    incrementalScan = scaleMap["incremental_scan"] as? Boolean ?: true,
                    fts5Enabled = scaleMap["fts5_enabled"] as? Boolean ?: true,
                    moduleEmbeddingBudget = (scaleMap["module_embedding_budget"] as? Number)?.toInt() ?: 8_000_000,
                    moduleTopK = (scaleMap["module_top_k"] as? Number)?.toInt() ?: 20,
                    similaritySearchLimit = (scaleMap["similarity_search_limit"] as? Number)?.toInt() ?: 50_000_000,
                    tokenBudget = (scaleMap["token_budget"] as? Number)?.toInt() ?: 128_000_000,
                    moduleSummaryCache = scaleMap["module_summary_cache"] as? Boolean ?: true
                ),
                decomposition = DecompositionConfig(
                    enabled = decompMap["enabled"] as? Boolean ?: true,
                    maxPartitions = (decompMap["max_partitions"] as? Number)?.toInt() ?: 32_000,
                    maxParallelLlmCalls = (decompMap["max_parallel_llm_calls"] as? Number)?.toInt() ?: 8_000,
                    synthesisEnabled = decompMap["synthesis_enabled"] as? Boolean ?: true,
                    heuristicOnly = decompMap["heuristic_only"] as? Boolean ?: false,
                    sessionTracking = decompMap["session_tracking"] as? Boolean ?: true
                ),
                evolution = EvolutionConfig(
                    enabled = evolutionMap["enabled"] as? Boolean ?: true,
                    collectTrainingData = evolutionMap["collect_training_data"] as? Boolean ?: true,
                    autoValidateOnSuccess = evolutionMap["auto_validate_on_success"] as? Boolean ?: true,
                    qualityThreshold = (evolutionMap["quality_threshold"] as? Number)?.toDouble() ?: 0.6,
                    productModel = evolutionMap["product_model"] as? String,
                    modelExportDir = evolutionMap["model_export_dir"] as? String ?: "~/.forge/models/exports",
                    datasetFormat = evolutionMap["dataset_format"] as? String ?: "jsonl",
                    piiFilterEnabled = evolutionMap["pii_filter_enabled"] as? Boolean ?: true
                ),
                agents = AgentOrchestrationConfig(
                    enabled = agentsMap["enabled"] as? Boolean ?: true,
                    alwaysHot = (agentsMap["always_hot"] as? List<*>)?.mapNotNull { it as? String }
                        ?: listOf("qwen3:1.7b", "qwen2.5:14b"),
                    specialistModels = (agentsMap["specialist_models"] as? Map<*, *>)?.mapNotNull { (k, v) ->
                        val key = k as? String ?: return@mapNotNull null
                        val value = v as? String ?: return@mapNotNull null
                        key to value
                    }?.toMap() ?: mapOf(
                        "CODE" to "qwen2.5-coder:14b",
                        "EMBED" to "qwen3-embedding:0.6b",
                        "SUMMARIZE" to "qwen2.5:14b",
                        "VISION" to "qwen2.5vl:7b"
                    ),
                    preloadOnStartup = agentsMap["preload_on_startup"] as? Boolean ?: true,
                    maxTotalMemoryGb = (agentsMap["max_total_memory_gb"] as? Number)?.toDouble() ?: 12.0,
                    heavyModelThresholdGb = (agentsMap["heavy_model_threshold_gb"] as? Number)?.toDouble() ?: 6.0,
                    keepAliveMinutes = (agentsMap["keep_alive_minutes"] as? Number)?.toInt() ?: 10
                )
            )
        }

        private fun mergeConfig(defaults: ForgeConfig, overrides: ForgeConfig): ForgeConfig {
            // Simple merge: override non-default values
            return overrides
        }
    }
}
