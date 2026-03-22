package forge.workspace

import forge.ForgeConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant

// ──────────────────────────────────────────────────────────────
// Public data classes
// ──────────────────────────────────────────────────────────────

data class Workspace(
    val path: Path,
    val db: Database,
    val repoPath: Path,
    val repos: List<RepoInfo> = emptyList()
)

data class WorkspaceInfo(
    val path: Path,
    val repoPath: String,
    val createdAt: String,
    val fileCount: Int
)

// ──────────────────────────────────────────────────────────────
// Internal meta model persisted as meta.json inside each workspace
// ──────────────────────────────────────────────────────────────

@Serializable
private data class WorkspaceMeta(
    val repoPath: String,
    val createdAt: String,
    val repoFingerprint: String = ""
)

// ──────────────────────────────────────────────────────────────
// WorkspaceManager
// ──────────────────────────────────────────────────────────────

class WorkspaceManager(private val config: ForgeConfig) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /**
     * Base directory that holds all workspace subdirectories.
     * Resolved from [ForgeConfig.resolvedWorkspaceDir].
     */
    private val baseDir: Path = config.resolvedWorkspaceDir()

    // ── Public API ───────────────────────────────────────────

    /**
     * Returns an existing workspace for [repoPath], or creates one if it
     * does not yet exist.  The workspace directory is keyed by the SHA-256
     * hash of the canonical repo path so that the same repo always maps to
     * the same workspace regardless of trailing slashes or symlinks.
     */
    fun getOrCreate(repoPath: Path): Workspace {
        val normalizedRepo = repoPath.toAbsolutePath().normalize()
        val workspacePath = getWorkspacePath(normalizedRepo)

        if (!Files.exists(workspacePath)) {
            Files.createDirectories(workspacePath)
            writeMetaJson(workspacePath, normalizedRepo)
        } else if (!Files.exists(workspacePath.resolve("meta.json"))) {
            // Workspace directory exists but meta.json is missing (e.g. after a partial cleanup).
            writeMetaJson(workspacePath, normalizedRepo)
        }

        val db = Database(workspacePath)
        return Workspace(path = workspacePath, db = db, repoPath = normalizedRepo)
    }

    /**
     * Deletes the entire workspace directory for [repoPath], including the
     * database and all cached artefacts.
     */
    fun clear(repoPath: Path) {
        val normalizedRepo = repoPath.toAbsolutePath().normalize()
        val workspacePath = getWorkspacePath(normalizedRepo)

        if (Files.exists(workspacePath)) {
            // Walk the tree in reverse depth-first order so files are deleted
            // before their parent directories.
            Files.walk(workspacePath)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        }
    }

    /**
     * Lists every workspace that currently exists on disk, together with
     * summary metadata read from each workspace's meta.json and database.
     */
    fun listWorkspaces(): List<WorkspaceInfo> {
        if (!Files.exists(baseDir)) return emptyList()

        val results = mutableListOf<WorkspaceInfo>()

        Files.newDirectoryStream(baseDir).use { stream ->
            for (entry in stream) {
                if (!Files.isDirectory(entry)) continue

                val metaFile = entry.resolve("meta.json")
                if (!Files.exists(metaFile)) continue

                val meta = try {
                    json.decodeFromString<WorkspaceMeta>(Files.readString(metaFile))
                } catch (_: Exception) {
                    continue
                }

                val fileCount = try {
                    val db = Database(entry)
                    val count = db.getFileCount()
                    db.close()
                    count
                } catch (_: Exception) {
                    0
                }

                results.add(
                    WorkspaceInfo(
                        path = entry,
                        repoPath = meta.repoPath,
                        createdAt = meta.createdAt,
                        fileCount = fileCount
                    )
                )
            }
        }

        return results
    }

    /**
     * Computes the workspace directory path for the given [repoPath].
     * Uses SHA-256 of the absolute, normalized path string.
     */
    fun getWorkspacePath(repoPath: Path): Path {
        val normalized = repoPath.toAbsolutePath().normalize().toString()
        val hash = sha256Hex(normalized)
        return baseDir.resolve(hash)
    }

    // ── Helpers ──────────────────────────────────────────────

    /**
     * Computes a fingerprint for the repo based on its path and top-level directory listing.
     * Used to verify that workspace index matches the current repo state.
     */
    fun computeRepoFingerprint(repoPath: Path): String {
        val normalized = repoPath.toAbsolutePath().normalize()
        val topItems = try {
            Files.list(normalized).use { stream ->
                stream.map { it.fileName.toString() }
                    .sorted()
                    .toList()
                    .joinToString(",")
            }
        } catch (_: Exception) { "" }
        return sha256Hex("${normalized}|${topItems}").take(16)
    }

    /**
     * Verifies that the workspace's stored fingerprint matches the current repo.
     * Returns true if they match or if no fingerprint was stored (backward compatibility).
     */
    fun verifyFingerprint(repoPath: Path): Boolean {
        val normalizedRepo = repoPath.toAbsolutePath().normalize()
        val workspacePath = getWorkspacePath(normalizedRepo)
        val metaFile = workspacePath.resolve("meta.json")
        if (!Files.exists(metaFile)) return false

        val meta = try {
            json.decodeFromString<WorkspaceMeta>(Files.readString(metaFile))
        } catch (_: Exception) { return false }

        // Backward compatibility: if no fingerprint stored, allow
        if (meta.repoFingerprint.isBlank()) return true

        val currentFingerprint = computeRepoFingerprint(normalizedRepo)
        return meta.repoFingerprint == currentFingerprint
    }

    private fun writeMetaJson(workspacePath: Path, repoPath: Path) {
        val meta = WorkspaceMeta(
            repoPath = repoPath.toString(),
            createdAt = Instant.now().toString(),
            repoFingerprint = computeRepoFingerprint(repoPath)
        )
        Files.writeString(workspacePath.resolve("meta.json"), json.encodeToString(meta))
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}
