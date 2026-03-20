package forge.web

import forge.ForgeConfig
import forge.workspace.Database
import forge.workspace.WorkspaceManager
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

/**
 * Thread-safe mutable state for the web server.
 * Tracks the currently selected repository, its workspace database,
 * and shared service references.
 */
class WebState(
    private val workspaceManager: WorkspaceManager,
    private val config: ForgeConfig
) {
    private val _repoPath = AtomicReference<String?>(null)
    private val _database = AtomicReference<Database?>(null)
    private val _workspacePath = AtomicReference<Path?>(null)

    /** Currently selected repository path. */
    val repoPath: String? get() = _repoPath.get()

    /** Database for the current workspace, or null if no repo selected / no DB exists. */
    val database: Database? get() = _database.get()

    /** Workspace directory path for the current repo. */
    val workspacePath: Path? get() = _workspacePath.get()

    /** Whether the current workspace has an initialized database. */
    val hasDatabase: Boolean get() = _database.get() != null

    /**
     * Switch to a new repository path.
     * Validates the path exists and is a directory, then opens the workspace database if available.
     * Returns true if the path was changed successfully.
     */
    fun setRepoPath(newPath: String): Boolean {
        val resolved = Path.of(newPath).toAbsolutePath().normalize()
        if (!Files.isDirectory(resolved)) return false

        val resolvedStr = resolved.toString()
        _repoPath.set(resolvedStr)

        // Close previous database
        _database.get()?.close()
        _database.set(null)
        _workspacePath.set(null)

        // Try to open existing workspace
        try {
            val wsPath = workspaceManager.getWorkspacePath(resolved)
            val dbFile = wsPath.resolve("workspace.db")
            if (Files.exists(dbFile)) {
                _workspacePath.set(wsPath)
                _database.set(Database(wsPath))
            } else {
                _workspacePath.set(wsPath)
            }
        } catch (_: Exception) {
            // Workspace doesn't exist yet — that's fine
        }

        return true
    }

    /**
     * Refresh the database connection (e.g., after analyze creates a new workspace).
     */
    fun refreshDatabase() {
        val repo = _repoPath.get() ?: return
        try {
            _database.get()?.close()
            val wsPath = workspaceManager.getWorkspacePath(Path.of(repo))
            val dbFile = wsPath.resolve("workspace.db")
            if (Files.exists(dbFile)) {
                _workspacePath.set(wsPath)
                _database.set(Database(wsPath))
            }
        } catch (_: Exception) { }
    }

    /** Compute the expected database path for a repo (even if it doesn't exist yet). */
    fun computeDbPath(repoPath: String): String {
        return try {
            workspaceManager.getWorkspacePath(Path.of(repoPath)).resolve("workspace.db").toString()
        } catch (_: Exception) {
            ""
        }
    }

    fun close() {
        _database.get()?.close()
        _database.set(null)
    }
}
