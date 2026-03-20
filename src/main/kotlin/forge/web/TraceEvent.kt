package forge.web

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * SSE trace events streamed to the frontend during pipeline execution.
 * Each event is serialized as JSON and sent as an SSE `data:` line.
 */
@Serializable
data class TraceEvent(
    val type: String,
    val timestamp: Long = System.currentTimeMillis(),
    // stage_started / stage_completed
    val stageName: String? = null,
    val stageIndex: Int? = null,
    val totalStages: Int? = null,
    val description: String? = null,
    val durationMs: Long? = null,
    val detail: String? = null,
    // intent_resolved
    val taskType: String? = null,
    val confidence: Float? = null,
    // model_selected
    val modelName: String? = null,
    // llm_token
    val token: String? = null,
    // error
    val errorMessage: String? = null,
    // done
    val response: String? = null,
    val model: String? = null,
    val totalDurationMs: Long? = null
) {
    companion object {
        private val json = Json { encodeDefaults = false }

        fun stageStarted(name: String, index: Int, total: Int, description: String) = TraceEvent(
            type = "stage_started",
            stageName = name,
            stageIndex = index,
            totalStages = total,
            description = description
        )

        fun stageCompleted(name: String, durationMs: Long, detail: String?) = TraceEvent(
            type = "stage_completed",
            stageName = name,
            durationMs = durationMs,
            detail = detail
        )

        fun intentResolved(taskType: String, confidence: Float) = TraceEvent(
            type = "intent_resolved",
            taskType = taskType,
            confidence = confidence
        )

        fun modelSelected(modelName: String) = TraceEvent(
            type = "model_selected",
            modelName = modelName
        )

        fun llmToken(token: String) = TraceEvent(
            type = "llm_token",
            token = token
        )

        fun error(stage: String, message: String) = TraceEvent(
            type = "error",
            stageName = stage,
            errorMessage = message
        )

        fun analysisProgress(current: Int, total: Int, moduleName: String, percent: Int) = TraceEvent(
            type = "analysis_progress",
            stageIndex = current,
            totalStages = total,
            stageName = moduleName,
            detail = "Analyzing module $current of $total: $moduleName",
            confidence = percent.toFloat()
        )

        fun done(response: String, taskType: String, model: String, totalDurationMs: Long) = TraceEvent(
            type = "done",
            response = response,
            taskType = taskType,
            model = model,
            totalDurationMs = totalDurationMs
        )
    }

    fun toSSE(): String {
        val jsonStr = json.encodeToString(this)
        return "data: $jsonStr\n\n"
    }
}
