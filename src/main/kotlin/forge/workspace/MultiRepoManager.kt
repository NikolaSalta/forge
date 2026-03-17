package forge.workspace

import forge.ForgeConfig
import forge.SatelliteRepo
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Info about a repo in the workspace.
 */
data class RepoInfo(
    val id: Int,
    val name: String,
    val localPath: Path,
    val isPrimary: Boolean
)

/**
 * Manages multiple repositories in a single workspace.
 * The primary repo (e.g., intellij-community) is the main target.
 * Satellite repos (kotlin, android, etc.) extend the search space.
 */
class MultiRepoManager(
    private val config: ForgeConfig,
    private val workspaceManager: WorkspaceManager
) {
    /**
     * Initialize workspace with primary repo and all configured satellites.
     */
    fun initializeMultiRepo(primaryRepoPath: Path): Workspace {
        val workspace = workspaceManager.getOrCreate(primaryRepoPath)
        val db = workspace.db

        // Register primary repo if not exists
        val repoName = primaryRepoPath.fileName.toString()
        val existingRepo = db.getRepoByName(repoName)
        if (existingRepo == null) {
            db.insertRepo(
                name = repoName,
                localPath = primaryRepoPath.toAbsolutePath().toString(),
                url = null,
                branch = null,
                isPrimary = true
            )
        }

        // Register configured satellites
        for (satellite in config.multiRepo.satellites) {
            val existingSat = db.getRepoByName(satellite.name)
            if (existingSat == null) {
                val localPath = ensureRepoCloned(satellite)
                db.insertRepo(
                    name = satellite.name,
                    localPath = localPath.toAbsolutePath().toString(),
                    url = satellite.url,
                    branch = satellite.branch,
                    isPrimary = false
                )
            }
        }

        return workspace
    }

    /**
     * Ensure a satellite repo is cloned locally.
     * If localPath is specified in config and exists, use it.
     * Otherwise, shallow-clone to the configured clone base directory.
     */
    fun ensureRepoCloned(satellite: SatelliteRepo): Path {
        // Check if explicit local path is provided
        if (satellite.localPath != null) {
            val explicitPath = Paths.get(satellite.localPath.replaceFirst("~", System.getProperty("user.home")))
            if (Files.exists(explicitPath)) {
                return explicitPath
            }
        }

        // Auto-clone to cloneBaseDir
        val baseDir = config.resolvedCloneBaseDir()
        val targetDir = baseDir.resolve(satellite.name)

        if (Files.exists(targetDir.resolve(".git"))) {
            return targetDir // Already cloned
        }

        if (!config.multiRepo.autoClone) {
            System.err.println("Satellite repo '${satellite.name}' not found and auto_clone is disabled.")
            System.err.println("  Expected at: $targetDir")
            System.err.println("  Enable auto_clone in forge.yaml or clone manually.")
            return targetDir
        }

        Files.createDirectories(baseDir)

        val args = mutableListOf("git", "clone")
        if (config.multiRepo.shallowClone) {
            args.addAll(listOf("--depth", "1"))
        }
        args.addAll(listOf("-b", satellite.branch, satellite.url, targetDir.toString()))

        println("Cloning satellite repo '${satellite.name}'...")
        println("  URL: ${satellite.url}")
        println("  Destination: $targetDir")

        try {
            val process = ProcessBuilder(args)
                .inheritIO()
                .start()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                System.err.println("Clone failed with exit code $exitCode")
            }
        } catch (e: Exception) {
            System.err.println("Failed to clone: ${e.message}")
        }

        return targetDir
    }

    /**
     * Dynamically connect a new satellite repo.
     */
    fun connectRepo(workspace: Workspace, name: String, url: String, localPath: Path? = null): RepoInfo? {
        val db = workspace.db

        // Check if already connected
        val existing = db.getRepoByName(name)
        if (existing != null) {
            return RepoInfo(existing.id, existing.name, Path.of(existing.localPath), existing.isPrimary)
        }

        val resolvedPath = if (localPath != null && Files.exists(localPath)) {
            localPath
        } else {
            // Try to clone
            ensureRepoCloned(SatelliteRepo(name = name, url = url))
        }

        val repoId = db.insertRepo(
            name = name,
            localPath = resolvedPath.toAbsolutePath().toString(),
            url = url,
            branch = "master",
            isPrimary = false
        )

        return if (repoId > 0) {
            RepoInfo(repoId, name, resolvedPath, false)
        } else null
    }

    /**
     * List all repos in the workspace.
     */
    fun listRepos(workspace: Workspace): List<RepoInfo> {
        return workspace.db.getAllRepos().map { repo ->
            RepoInfo(
                id = repo.id,
                name = repo.name,
                localPath = Path.of(repo.localPath),
                isPrimary = repo.isPrimary
            )
        }
    }

    /**
     * Get a specific repo by name.
     */
    fun getRepo(workspace: Workspace, name: String): RepoInfo? {
        val repo = workspace.db.getRepoByName(name) ?: return null
        return RepoInfo(repo.id, repo.name, Path.of(repo.localPath), repo.isPrimary)
    }
}
