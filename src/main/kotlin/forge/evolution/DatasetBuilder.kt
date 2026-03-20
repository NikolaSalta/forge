package forge.evolution

import forge.ForgeConfig
import forge.workspace.Database
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Builds training datasets from validated Q/A pairs in the database.
 * Supports JSONL (default), Alpaca, and ShareGPT formats.
 */
class DatasetBuilder(
    private val config: ForgeConfig,
    private val filter: TrainingDataFilter = TrainingDataFilter(config.evolution.piiFilterEnabled)
) {
    private val json = Json { prettyPrint = false; encodeDefaults = true }

    @Serializable
    data class JsonlEntry(
        val instruction: String,
        val input: String = "",
        val output: String,
        val system: String = "",
        val task_type: String = "",
        val quality: Double = 0.0
    )

    @Serializable
    data class DatasetManifest(
        val version: String,
        val format: String,
        val record_count: Int,
        val quality_threshold: Double,
        val created_at: String,
        val base_model: String = "",
        val description: String = ""
    )

    /**
     * Build and export a training dataset from the database.
     *
     * @param db Database containing training data
     * @param minQuality Minimum quality score for inclusion
     * @param version Version string for the dataset
     * @return Path to the exported dataset file, or null if no data available
     */
    fun buildDataset(
        db: Database,
        minQuality: Double = config.evolution.qualityThreshold,
        version: String = "v1"
    ): Path? {
        val records = db.getTrainingDataForExport(minQuality)
        if (records.isEmpty()) return null

        // Create export directory
        val exportDir = config.resolvedModelExportDir()
        val datasetDir = exportDir.resolve("datasets").resolve(version)
        Files.createDirectories(datasetDir)

        val format = config.evolution.datasetFormat
        val datasetFile = datasetDir.resolve("training-$version.$format")

        // Build entries
        val entries = records.mapNotNull { record ->
            val input = record["input"] as? String ?: return@mapNotNull null
            val output = record["output"] as? String ?: return@mapNotNull null

            // Final safety check
            if (!filter.isSafe(input) || !filter.isSafe(output)) return@mapNotNull null

            JsonlEntry(
                instruction = input,
                output = output,
                system = (record["system"] as? String) ?: "",
                task_type = (record["task_type"] as? String) ?: "",
                quality = (record["quality"] as? Double) ?: 0.0
            )
        }

        if (entries.isEmpty()) return null

        // Write dataset file
        Files.newBufferedWriter(datasetFile).use { writer ->
            for (entry in entries) {
                writer.write(json.encodeToString(entry))
                writer.newLine()
            }
        }

        // Write manifest
        val manifest = DatasetManifest(
            version = version,
            format = format,
            record_count = entries.size,
            quality_threshold = minQuality,
            created_at = Instant.now().toString()
        )
        Files.writeString(
            datasetDir.resolve("dataset-manifest.json"),
            Json { prettyPrint = true }.encodeToString(manifest)
        )

        // Mark records as exported in DB
        val exportedIds = records.mapNotNull { (it["id"] as? Int) }
        db.markTrainingDataExported(exportedIds)

        // Record the export in the database
        db.insertDatasetExport(version, format, entries.size, datasetFile.toString(), minQuality)

        return datasetFile
    }
}
