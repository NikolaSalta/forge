package forge.llm

import forge.AgentOrchestrationConfig
import forge.core.ModelRole
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory

/**
 * Three-agent orchestrator for memory-aware model management on constrained hardware.
 *
 * Agent architecture:
 *   Agent 1 (Classifier) → qwen3:1.7b          → Always hot
 *   Agent 2 (Reasoner)   → deepseek-r1:8b       → Always hot
 *   Agent 3 (Specialist)  → on-demand, one of:
 *       CODE      → qwen2.5-coder:14b  (~9 GB, heavy)
 *       EMBED     → qwen3-embedding:0.6b (~0.5 GB, light)
 *       SUMMARIZE → qwen2.5:14b         (~9 GB, heavy)
 *       VISION    → qwen2.5vl:7b        (~4.5 GB, light-medium)
 *
 * Memory strategy on 16 GB M3 (12 GB usable):
 *   - Agents 1+2 always loaded: ~6.5 GB
 *   - Light specialists (embed, vision): load alongside 1+2
 *   - Heavy specialists (code, summarize): unload Agent 2 → load specialist → use → unload → reload Agent 2
 */
class AgentOrchestrator(
    private val config: AgentOrchestrationConfig,
    private val client: OllamaClient
) {
    private val log = LoggerFactory.getLogger(AgentOrchestrator::class.java)
    private val mutex = Mutex()

    @Volatile private var totalMemoryBytes: Long = 0
    @Volatile private var initialized: Boolean = false
    @Volatile private var activeSpecialist: String? = null
    @Volatile private var reasonerUnloaded: Boolean = false

    // ── Initialization ──────────────────────────────────────────────────────

    suspend fun initialize() = mutex.withLock {
        if (initialized) return@withLock

        totalMemoryBytes = client.getSystemMemoryBytes()
        log.info("Agent orchestrator: system RAM = {:.1f} GB, max model budget = {:.1f} GB",
            totalMemoryBytes / GB, config.maxTotalMemoryGb)

        if (config.preloadOnStartup) {
            for (model in config.alwaysHot) {
                log.info("Preloading always-hot agent: {}", model)
                val ok = client.preloadModel(model, config.keepAliveMinutes)
                if (ok) log.info("  ✓ {} loaded", model)
                else log.warn("  ✗ {} failed to preload", model)
            }
        }

        initialized = true
    }

    // ── Agent readiness ─────────────────────────────────────────────────────

    /**
     * Ensure the model for the given role is loaded and ready.
     * For always-hot agents, this is a no-op (they're already loaded).
     * For specialists, this handles the load/unload dance based on memory.
     */
    suspend fun ensureAgentReady(role: ModelRole, modelName: String): Boolean {
        if (!config.enabled) return true

        // Always-hot agents: just verify they're still loaded
        if (modelName in config.alwaysHot) {
            return ensureHotAgentLoaded(modelName)
        }

        // Specialist agent: check if it's heavy or light
        return mutex.withLock {
            loadSpecialist(modelName)
        }
    }

    /**
     * Release the currently loaded specialist and restore always-hot agents.
     * Call this after a specialist task completes.
     */
    suspend fun releaseSpecialist() = mutex.withLock {
        val specialist = activeSpecialist ?: return@withLock
        log.info("Releasing specialist: {}", specialist)

        // Unload the specialist
        client.unloadModel(specialist)
        activeSpecialist = null

        // If we had to unload the reasoner, reload it
        if (reasonerUnloaded) {
            val reasoner = config.alwaysHot.getOrNull(1) // deepseek-r1:8b
            if (reasoner != null) {
                log.info("Restoring always-hot reasoner: {}", reasoner)
                client.preloadModel(reasoner, config.keepAliveMinutes)
            }
            reasonerUnloaded = false
        }
    }

    // ── Status ──────────────────────────────────────────────────────────────

    suspend fun getStatus(): AgentOrchestratorStatus {
        val loaded = client.getLoadedModels()
        val usedGb = loaded.sumOf { it.sizeGb }
        val totalGb = totalMemoryBytes / GB
        val availableGb = config.maxTotalMemoryGb - usedGb

        return AgentOrchestratorStatus(
            enabled = config.enabled,
            agents = listOf(
                AgentInfo("Classifier", config.alwaysHot.getOrElse(0) { "qwen3:1.7b" },
                    "CLASSIFY", isHot = true,
                    isLoaded = loaded.any { matchesModel(it.name, config.alwaysHot.getOrElse(0) { "" }) }),
                AgentInfo("Reasoner", config.alwaysHot.getOrElse(1) { "deepseek-r1:8b" },
                    "REASON", isHot = true,
                    isLoaded = loaded.any { matchesModel(it.name, config.alwaysHot.getOrElse(1) { "" }) }),
                AgentInfo("Specialist", activeSpecialist ?: "(idle)",
                    activeSpecialist?.let { guessRole(it) } ?: "IDLE",
                    isHot = false,
                    isLoaded = activeSpecialist != null && loaded.any { matchesModel(it.name, activeSpecialist!!) })
            ),
            loadedModels = loaded.map { LoadedModelInfo(it.name, it.sizeGb, it.expiresAt) },
            totalMemoryGb = totalGb,
            usedMemoryGb = usedGb,
            availableMemoryGb = availableGb,
            canLoadMore = availableGb > 1.0
        )
    }

    // ── Internal helpers ────────────────────────────────────────────────────

    private suspend fun ensureHotAgentLoaded(modelName: String): Boolean {
        val loaded = client.getLoadedModels()
        if (loaded.any { matchesModel(it.name, modelName) }) return true

        log.info("Hot agent {} not loaded, preloading...", modelName)
        return client.preloadModel(modelName, config.keepAliveMinutes)
    }

    private suspend fun loadSpecialist(modelName: String): Boolean {
        // Already loaded?
        if (activeSpecialist == modelName) {
            val loaded = client.getLoadedModels()
            if (loaded.any { matchesModel(it.name, modelName) }) return true
        }

        // Release previous specialist if different
        if (activeSpecialist != null && activeSpecialist != modelName) {
            client.unloadModel(activeSpecialist!!)
            activeSpecialist = null
        }

        // Check memory: is this a heavy specialist?
        val isHeavy = isHeavyModel(modelName)

        if (isHeavy) {
            // Heavy specialist: unload reasoner to free memory
            val reasoner = config.alwaysHot.getOrNull(1)
            if (reasoner != null && !reasonerUnloaded) {
                log.info("Heavy specialist {} requested — unloading reasoner {} to free memory", modelName, reasoner)
                client.unloadModel(reasoner)
                reasonerUnloaded = true
                // Give Ollama a moment to release memory
                kotlinx.coroutines.delay(500)
            }
        }

        log.info("Loading specialist: {}", modelName)
        val ok = client.preloadModel(modelName, config.keepAliveMinutes)
        if (ok) {
            activeSpecialist = modelName
            log.info("  ✓ Specialist {} ready", modelName)
        } else {
            log.warn("  ✗ Failed to load specialist {}", modelName)
        }
        return ok
    }

    private fun isHeavyModel(modelName: String): Boolean {
        // Models with 14b+ parameters are heavy (>6 GB)
        val lower = modelName.lowercase()
        return lower.contains("14b") || lower.contains("20b") || lower.contains("13b")
    }

    private fun matchesModel(loadedName: String, configName: String): Boolean {
        if (loadedName == configName) return true
        // Handle tag variations: "qwen3:1.7b" matches "qwen3:1.7b-fp16" etc.
        val baseName = configName.split(":").firstOrNull() ?: configName
        return loadedName.startsWith(baseName)
    }

    private fun guessRole(modelName: String): String {
        for ((role, model) in config.specialistModels) {
            if (model == modelName || matchesModel(modelName, model)) return role
        }
        return "SPECIALIST"
    }

    companion object {
        private const val GB = 1024.0 * 1024.0 * 1024.0
    }
}

// ── Status data classes ─────────────────────────────────────────────────────────

data class AgentOrchestratorStatus(
    val enabled: Boolean,
    val agents: List<AgentInfo>,
    val loadedModels: List<LoadedModelInfo>,
    val totalMemoryGb: Double,
    val usedMemoryGb: Double,
    val availableMemoryGb: Double,
    val canLoadMore: Boolean
)

data class AgentInfo(
    val name: String,
    val model: String,
    val role: String,
    val isHot: Boolean,
    val isLoaded: Boolean
)

data class LoadedModelInfo(
    val name: String,
    val sizeGb: Double,
    val expiresAt: String
)
