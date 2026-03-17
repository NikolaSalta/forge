package forge.workspace

import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Statement
import java.time.Instant

// ──────────────────────────────────────────────────────────────
// Data classes
// ──────────────────────────────────────────────────────────────

data class FileRecord(
    val id: Int,
    val path: String,
    val relativePath: String,
    val language: String?,
    val sizeBytes: Long?,
    val lineCount: Int?,
    val sha256: String?,
    val category: String?,
    val scannedAt: String?
)

data class ChunkRecord(
    val id: Int,
    val fileId: Int,
    val content: String,
    val startLine: Int?,
    val endLine: Int?,
    val chunkType: String?,
    val symbolName: String?,
    val language: String?,
    val embedding: ByteArray?,
    val createdAt: String?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChunkRecord) return false
        return id == other.id
    }

    override fun hashCode(): Int = id
}

data class EvidenceRecord(
    val id: Int,
    val taskId: String,
    val category: String,
    val key: String,
    val value: String,
    val confidence: Double,
    val sourceFile: String?,
    val createdAt: String?
)

data class TaskRecord(
    val id: String,
    val type: String,
    val intent: String?,
    val status: String?,
    val modelUsed: String?,
    val repoPath: String?,
    val resultSummary: String?,
    val createdAt: String?,
    val updatedAt: String?
)

data class RepoRecord(
    val id: Int,
    val name: String,
    val localPath: String,
    val url: String?,
    val branch: String?,
    val isPrimary: Boolean,
    val lastScan: String?,
    val commitSha: String?
)

data class ModuleRecord(
    val id: Int,
    val repoId: Int,
    val name: String,
    val path: String,
    val pluginXml: String?,
    val moduleType: String?,
    val dependencies: String?,
    val summary: String?,
    val summaryAt: String?,
    val fileCount: Int
)

data class ExtensionPointRecord(
    val id: Int,
    val moduleId: Int,
    val qualifiedName: String,
    val interfaceFqn: String?,
    val beanClass: String?,
    val area: String?,
    val description: String?
)

data class EpImplementationRecord(
    val id: Int,
    val extensionPointId: Int,
    val moduleId: Int,
    val implementationFqn: String,
    val pluginXmlPath: String?
)

// ──────────────────────────────────────────────────────────────
// Database wrapper
// ──────────────────────────────────────────────────────────────

class Database(workspacePath: Path) {

    private val dbPath: String = workspacePath.resolve("workspace.db").toString()
    private val lock = Any()

    @Volatile
    private var connection: Connection? = null

    init {
        // Force-load the SQLite JDBC driver
        Class.forName("org.sqlite.JDBC")
        // Use a dedicated connection for schema setup so the shared connection
        // is not closed by the .use{} block.
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("PRAGMA journal_mode=WAL")
                stmt.execute("PRAGMA busy_timeout=30000")
                stmt.execute("PRAGMA foreign_keys=ON")
                createTables(stmt)
                createIndexes(stmt)
            }
        }
        migrateFilesTable()
        createFTS5()
    }

    // ── Connection management ────────────────────────────────

    private fun getConnection(): Connection {
        synchronized(lock) {
            var conn = connection
            if (conn == null || conn.isClosed) {
                conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")
                conn.createStatement().use { stmt ->
                    stmt.execute("PRAGMA journal_mode=WAL")
                    stmt.execute("PRAGMA busy_timeout=30000")
                }
                connection = conn
            }
            return conn
        }
    }

    fun close() {
        synchronized(lock) {
            connection?.let { conn ->
                if (!conn.isClosed) {
                    conn.close()
                }
            }
            connection = null
        }
    }

    // ── Schema creation ──────────────────────────────────────

    private fun createTables(stmt: Statement) {
        stmt.execute(
            """
            CREATE TABLE IF NOT EXISTS project_meta (
                key        TEXT PRIMARY KEY,
                value      TEXT,
                updated_at TEXT
            )
            """.trimIndent()
        )

        stmt.execute(
            """
            CREATE TABLE IF NOT EXISTS files (
                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                path          TEXT UNIQUE NOT NULL,
                relative_path TEXT NOT NULL,
                language      TEXT,
                size_bytes    INTEGER,
                line_count    INTEGER,
                sha256        TEXT,
                category      TEXT,
                scanned_at    TEXT
            )
            """.trimIndent()
        )

        stmt.execute(
            """
            CREATE TABLE IF NOT EXISTS chunks (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                file_id     INTEGER REFERENCES files(id) ON DELETE CASCADE,
                content     TEXT NOT NULL,
                start_line  INTEGER,
                end_line    INTEGER,
                chunk_type  TEXT,
                symbol_name TEXT,
                language    TEXT,
                embedding   BLOB,
                created_at  TEXT
            )
            """.trimIndent()
        )

        stmt.execute(
            """
            CREATE TABLE IF NOT EXISTS evidence (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                task_id     TEXT NOT NULL,
                category    TEXT NOT NULL,
                key         TEXT NOT NULL,
                value       TEXT NOT NULL,
                confidence  REAL DEFAULT 1.0,
                source_file TEXT,
                created_at  TEXT
            )
            """.trimIndent()
        )

        stmt.execute(
            """
            CREATE TABLE IF NOT EXISTS tasks (
                id             TEXT PRIMARY KEY,
                type           TEXT NOT NULL,
                intent         TEXT,
                status         TEXT DEFAULT 'created',
                model_used     TEXT,
                repo_path      TEXT,
                result_summary TEXT,
                created_at     TEXT,
                updated_at     TEXT
            )
            """.trimIndent()
        )

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS repos (
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                name       TEXT UNIQUE NOT NULL,
                local_path TEXT NOT NULL,
                url        TEXT,
                branch     TEXT DEFAULT 'master',
                is_primary INTEGER DEFAULT 0,
                last_scan  TEXT,
                commit_sha TEXT
            )
        """.trimIndent())

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS modules (
                id             INTEGER PRIMARY KEY AUTOINCREMENT,
                repo_id        INTEGER REFERENCES repos(id),
                name           TEXT NOT NULL,
                path           TEXT NOT NULL,
                plugin_xml     TEXT,
                module_type    TEXT,
                dependencies   TEXT,
                summary        TEXT,
                summary_at     TEXT,
                file_count     INTEGER DEFAULT 0,
                UNIQUE(repo_id, path)
            )
        """.trimIndent())

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS extension_points (
                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                module_id       INTEGER REFERENCES modules(id),
                qualified_name  TEXT UNIQUE NOT NULL,
                interface_fqn   TEXT,
                bean_class      TEXT,
                area            TEXT,
                description     TEXT
            )
        """.trimIndent())

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS ep_implementations (
                id                  INTEGER PRIMARY KEY AUTOINCREMENT,
                extension_point_id  INTEGER REFERENCES extension_points(id),
                module_id           INTEGER REFERENCES modules(id),
                implementation_fqn  TEXT NOT NULL,
                plugin_xml_path     TEXT
            )
        """.trimIndent())
    }

    private fun createIndexes(stmt: Statement) {
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_chunks_file_id    ON chunks(file_id)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_chunks_chunk_type ON chunks(chunk_type)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_evidence_task_id  ON evidence(task_id)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_evidence_category ON evidence(category)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_files_category    ON files(category)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_files_language    ON files(language)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_modules_repo_id   ON modules(repo_id)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_ep_module_id      ON extension_points(module_id)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_ep_impl_ep_id     ON ep_implementations(extension_point_id)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_ep_impl_mod_id    ON ep_implementations(module_id)")
    }

    private fun migrateFilesTable() {
        synchronized(lock) {
            val conn = getConnection()
            val meta = conn.metaData
            val cols = meta.getColumns(null, null, "files", "repo_id")
            if (!cols.next()) {
                conn.createStatement().use { stmt ->
                    stmt.execute("ALTER TABLE files ADD COLUMN repo_id INTEGER REFERENCES repos(id)")
                    stmt.execute("ALTER TABLE files ADD COLUMN module_id INTEGER REFERENCES modules(id)")
                }
            }
            cols.close()
            // Create indexes on the new columns (after migration ensures they exist)
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_files_repo_id   ON files(repo_id)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_files_module_id ON files(module_id)")
            }
        }
    }

    private fun createFTS5() {
        synchronized(lock) {
            try {
                getConnection().createStatement().use { stmt ->
                    stmt.execute("""
                        CREATE VIRTUAL TABLE IF NOT EXISTS chunks_fts
                        USING fts5(content, symbol_name, language, content=chunks, content_rowid=id)
                    """.trimIndent())
                }
            } catch (e: Exception) {
                System.err.println("FTS5 not available: ${e.message}")
            }
        }
    }

    // ── Files CRUD ───────────────────────────────────────────

    fun insertFile(
        path: String,
        relativePath: String,
        language: String?,
        sizeBytes: Long?,
        lineCount: Int?,
        sha256: String?,
        category: String?
    ): Int {
        synchronized(lock) {
            val conn = getConnection()
            val sql = """
                INSERT OR REPLACE INTO files (path, relative_path, language, size_bytes, line_count, sha256, category, scanned_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
            conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { ps ->
                ps.setString(1, path)
                ps.setString(2, relativePath)
                ps.setString(3, language)
                if (sizeBytes != null) ps.setLong(4, sizeBytes) else ps.setNull(4, java.sql.Types.INTEGER)
                if (lineCount != null) ps.setInt(5, lineCount) else ps.setNull(5, java.sql.Types.INTEGER)
                ps.setString(6, sha256)
                ps.setString(7, category)
                ps.setString(8, Instant.now().toString())
                ps.executeUpdate()
                ps.generatedKeys.use { rs ->
                    return if (rs.next()) rs.getInt(1) else -1
                }
            }
        }
    }

    fun getFileCount(): Int {
        synchronized(lock) {
            val conn = getConnection()
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT COUNT(*) FROM files").use { rs ->
                    return if (rs.next()) rs.getInt(1) else 0
                }
            }
        }
    }

    fun getFilesByCategory(category: String): List<FileRecord> {
        synchronized(lock) {
            val conn = getConnection()
            conn.prepareStatement("SELECT * FROM files WHERE category = ?").use { ps ->
                ps.setString(1, category)
                ps.executeQuery().use { rs ->
                    return rs.toFileRecords()
                }
            }
        }
    }

    fun getAllFiles(): List<FileRecord> {
        synchronized(lock) {
            val conn = getConnection()
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT * FROM files").use { rs ->
                    return rs.toFileRecords()
                }
            }
        }
    }

    fun getFileById(fileId: Int): FileRecord? {
        synchronized(lock) {
            val conn = getConnection()
            conn.prepareStatement("SELECT * FROM files WHERE id = ?").use { ps ->
                ps.setInt(1, fileId)
                ps.executeQuery().use { rs ->
                    val records = rs.toFileRecords()
                    return records.firstOrNull()
                }
            }
        }
    }

    // ── Chunks CRUD ──────────────────────────────────────────

    fun insertChunk(
        fileId: Int,
        content: String,
        startLine: Int?,
        endLine: Int?,
        chunkType: String?,
        symbolName: String?,
        language: String?
    ): Int {
        synchronized(lock) {
            val conn = getConnection()
            val sql = """
                INSERT INTO chunks (file_id, content, start_line, end_line, chunk_type, symbol_name, language, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
            conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { ps ->
                ps.setInt(1, fileId)
                ps.setString(2, content)
                if (startLine != null) ps.setInt(3, startLine) else ps.setNull(3, java.sql.Types.INTEGER)
                if (endLine != null) ps.setInt(4, endLine) else ps.setNull(4, java.sql.Types.INTEGER)
                ps.setString(5, chunkType)
                ps.setString(6, symbolName)
                ps.setString(7, language)
                ps.setString(8, Instant.now().toString())
                ps.executeUpdate()
                ps.generatedKeys.use { rs ->
                    return if (rs.next()) rs.getInt(1) else -1
                }
            }
        }
    }

    fun updateChunkEmbedding(chunkId: Int, embedding: ByteArray) {
        synchronized(lock) {
            val conn = getConnection()
            conn.prepareStatement("UPDATE chunks SET embedding = ? WHERE id = ?").use { ps ->
                ps.setBytes(1, embedding)
                ps.setInt(2, chunkId)
                ps.executeUpdate()
            }
        }
    }

    fun getChunkCount(): Int {
        synchronized(lock) {
            val conn = getConnection()
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT COUNT(*) FROM chunks").use { rs ->
                    return if (rs.next()) rs.getInt(1) else 0
                }
            }
        }
    }

    fun getChunksWithEmbeddings(): List<ChunkRecord> {
        synchronized(lock) {
            val conn = getConnection()
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT * FROM chunks WHERE embedding IS NOT NULL").use { rs ->
                    return rs.toChunkRecords()
                }
            }
        }
    }

    /**
     * Returns chunks that do NOT have embeddings yet, up to [limit].
     * Used by [EmbeddingStore] to process batches incrementally.
     */
    fun getChunksWithoutEmbeddings(limit: Int): List<ChunkRecord> {
        synchronized(lock) {
            val conn = getConnection()
            conn.prepareStatement("SELECT * FROM chunks WHERE embedding IS NULL LIMIT ?").use { ps ->
                ps.setInt(1, limit)
                ps.executeQuery().use { rs ->
                    return rs.toChunkRecords()
                }
            }
        }
    }

    // ── Evidence CRUD ────────────────────────────────────────

    fun insertEvidence(
        taskId: String,
        category: String,
        key: String,
        value: String,
        confidence: Double = 1.0,
        sourceFile: String? = null
    ) {
        synchronized(lock) {
            val conn = getConnection()
            val sql = """
                INSERT INTO evidence (task_id, category, key, value, confidence, source_file, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, taskId)
                ps.setString(2, category)
                ps.setString(3, key)
                ps.setString(4, value)
                ps.setDouble(5, confidence)
                ps.setString(6, sourceFile)
                ps.setString(7, Instant.now().toString())
                ps.executeUpdate()
            }
        }
    }

    fun getEvidenceByTask(taskId: String): List<EvidenceRecord> {
        synchronized(lock) {
            val conn = getConnection()
            conn.prepareStatement("SELECT * FROM evidence WHERE task_id = ? ORDER BY id").use { ps ->
                ps.setString(1, taskId)
                ps.executeQuery().use { rs ->
                    return rs.toEvidenceRecords()
                }
            }
        }
    }

    fun getEvidenceCategoriesByTask(taskId: String): Set<String> {
        synchronized(lock) {
            val conn = getConnection()
            conn.prepareStatement("SELECT DISTINCT category FROM evidence WHERE task_id = ?").use { ps ->
                ps.setString(1, taskId)
                ps.executeQuery().use { rs ->
                    val categories = mutableSetOf<String>()
                    while (rs.next()) {
                        categories.add(rs.getString("category"))
                    }
                    return categories
                }
            }
        }
    }

    fun getEvidenceByCategory(taskId: String, category: String): List<EvidenceRecord> {
        synchronized(lock) {
            val conn = getConnection()
            conn.prepareStatement("SELECT * FROM evidence WHERE task_id = ? AND category = ?").use { ps ->
                ps.setString(1, taskId)
                ps.setString(2, category)
                ps.executeQuery().use { rs ->
                    return rs.toEvidenceRecords()
                }
            }
        }
    }

    // ── Tasks CRUD ───────────────────────────────────────────

    fun insertTask(id: String, type: String, intent: String?, repoPath: String?) {
        synchronized(lock) {
            val conn = getConnection()
            val now = Instant.now().toString()
            val sql = """
                INSERT OR REPLACE INTO tasks (id, type, intent, status, repo_path, created_at, updated_at)
                VALUES (?, ?, ?, 'created', ?, ?, ?)
            """.trimIndent()
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, id)
                ps.setString(2, type)
                ps.setString(3, intent)
                ps.setString(4, repoPath)
                ps.setString(5, now)
                ps.setString(6, now)
                ps.executeUpdate()
            }
        }
    }

    fun updateTaskStatus(taskId: String, status: String) {
        synchronized(lock) {
            val conn = getConnection()
            conn.prepareStatement("UPDATE tasks SET status = ?, updated_at = ? WHERE id = ?").use { ps ->
                ps.setString(1, status)
                ps.setString(2, Instant.now().toString())
                ps.setString(3, taskId)
                ps.executeUpdate()
            }
        }
    }

    // ── Meta CRUD ────────────────────────────────────────────

    fun getMeta(key: String): String? {
        synchronized(lock) {
            val conn = getConnection()
            conn.prepareStatement("SELECT value FROM project_meta WHERE key = ?").use { ps ->
                ps.setString(1, key)
                ps.executeQuery().use { rs ->
                    return if (rs.next()) rs.getString("value") else null
                }
            }
        }
    }

    fun setMeta(key: String, value: String) {
        synchronized(lock) {
            val conn = getConnection()
            val sql = """
                INSERT OR REPLACE INTO project_meta (key, value, updated_at)
                VALUES (?, ?, ?)
            """.trimIndent()
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, key)
                ps.setString(2, value)
                ps.setString(3, Instant.now().toString())
                ps.executeUpdate()
            }
        }
    }

    // ── Batch insertion APIs ──────────────────────────────────

    data class FileInsertData(
        val path: String,
        val relativePath: String,
        val language: String?,
        val sizeBytes: Long,
        val lineCount: Int,
        val sha256: String?,
        val category: String,
        val repoId: Int? = null,
        val moduleId: Int? = null,
        val scannedAt: String
    )

    fun insertFilesBatch(files: List<FileInsertData>) {
        if (files.isEmpty()) return
        synchronized(lock) {
            val conn = getConnection()
            val wasAutoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                val sql = """INSERT OR REPLACE INTO files
                    (path, relative_path, language, size_bytes, line_count, sha256, category, repo_id, module_id, scanned_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".trimIndent()
                conn.prepareStatement(sql).use { ps ->
                    for (file in files) {
                        ps.setString(1, file.path)
                        ps.setString(2, file.relativePath)
                        ps.setString(3, file.language)
                        ps.setLong(4, file.sizeBytes)
                        ps.setInt(5, file.lineCount)
                        ps.setString(6, file.sha256)
                        ps.setString(7, file.category)
                        if (file.repoId != null) ps.setInt(8, file.repoId) else ps.setNull(8, java.sql.Types.INTEGER)
                        if (file.moduleId != null) ps.setInt(9, file.moduleId) else ps.setNull(9, java.sql.Types.INTEGER)
                        ps.setString(10, file.scannedAt)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = wasAutoCommit
            }
        }
    }

    data class ChunkInsertData(
        val fileId: Int,
        val content: String,
        val startLine: Int,
        val endLine: Int,
        val chunkType: String?,
        val symbolName: String?,
        val language: String?,
        val createdAt: String
    )

    fun insertChunksBatch(chunks: List<ChunkInsertData>) {
        if (chunks.isEmpty()) return
        synchronized(lock) {
            val conn = getConnection()
            val wasAutoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                val sql = """INSERT INTO chunks
                    (file_id, content, start_line, end_line, chunk_type, symbol_name, language, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)""".trimIndent()
                conn.prepareStatement(sql).use { ps ->
                    for (chunk in chunks) {
                        ps.setInt(1, chunk.fileId)
                        ps.setString(2, chunk.content)
                        ps.setInt(3, chunk.startLine)
                        ps.setInt(4, chunk.endLine)
                        ps.setString(5, chunk.chunkType)
                        ps.setString(6, chunk.symbolName)
                        ps.setString(7, chunk.language)
                        ps.setString(8, chunk.createdAt)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = wasAutoCommit
            }
        }
    }

    // ── Repos CRUD ────────────────────────────────────────────

    fun insertRepo(name: String, localPath: String, url: String?, branch: String?, isPrimary: Boolean): Int {
        synchronized(lock) {
            val conn = getConnection()
            val sql = """INSERT OR REPLACE INTO repos (name, local_path, url, branch, is_primary) VALUES (?, ?, ?, ?, ?)"""
            conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { ps ->
                ps.setString(1, name)
                ps.setString(2, localPath)
                ps.setString(3, url)
                ps.setString(4, branch)
                ps.setInt(5, if (isPrimary) 1 else 0)
                ps.executeUpdate()
                val keys = ps.generatedKeys
                return if (keys.next()) keys.getInt(1) else -1
            }
        }
    }

    fun getRepoByName(name: String): RepoRecord? {
        synchronized(lock) {
            val conn = getConnection()
            conn.prepareStatement("SELECT * FROM repos WHERE name = ?").use { ps ->
                ps.setString(1, name)
                val rs = ps.executeQuery()
                return if (rs.next()) RepoRecord(
                    id = rs.getInt("id"), name = rs.getString("name"),
                    localPath = rs.getString("local_path"), url = rs.getString("url"),
                    branch = rs.getString("branch"), isPrimary = rs.getInt("is_primary") == 1,
                    lastScan = rs.getString("last_scan"), commitSha = rs.getString("commit_sha")
                ) else null
            }
        }
    }

    fun getRepoByPath(localPath: String): RepoRecord? {
        synchronized(lock) {
            val conn = getConnection()
            conn.prepareStatement("SELECT * FROM repos WHERE local_path = ?").use { ps ->
                ps.setString(1, localPath)
                val rs = ps.executeQuery()
                return if (rs.next()) RepoRecord(
                    id = rs.getInt("id"), name = rs.getString("name"),
                    localPath = rs.getString("local_path"), url = rs.getString("url"),
                    branch = rs.getString("branch"), isPrimary = rs.getInt("is_primary") == 1,
                    lastScan = rs.getString("last_scan"), commitSha = rs.getString("commit_sha")
                ) else null
            }
        }
    }

    fun getAllRepos(): List<RepoRecord> {
        synchronized(lock) {
            val conn = getConnection()
            val rs = conn.createStatement().executeQuery("SELECT * FROM repos")
            val result = mutableListOf<RepoRecord>()
            while (rs.next()) {
                result.add(RepoRecord(
                    id = rs.getInt("id"), name = rs.getString("name"),
                    localPath = rs.getString("local_path"), url = rs.getString("url"),
                    branch = rs.getString("branch"), isPrimary = rs.getInt("is_primary") == 1,
                    lastScan = rs.getString("last_scan"), commitSha = rs.getString("commit_sha")
                ))
            }
            return result
        }
    }

    fun updateRepoScan(repoId: Int, commitSha: String) {
        synchronized(lock) {
            val conn = getConnection()
            conn.prepareStatement("UPDATE repos SET last_scan = ?, commit_sha = ? WHERE id = ?").use { ps ->
                ps.setString(1, Instant.now().toString())
                ps.setString(2, commitSha)
                ps.setInt(3, repoId)
                ps.executeUpdate()
            }
        }
    }

    // ── Modules CRUD ──────────────────────────────────────────

    fun insertModule(repoId: Int, name: String, path: String, pluginXml: String?, moduleType: String?, dependencies: String?): Int {
        synchronized(lock) {
            val conn = getConnection()
            val sql = """INSERT OR REPLACE INTO modules (repo_id, name, path, plugin_xml, module_type, dependencies) VALUES (?, ?, ?, ?, ?, ?)"""
            conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { ps ->
                ps.setInt(1, repoId)
                ps.setString(2, name)
                ps.setString(3, path)
                ps.setString(4, pluginXml)
                ps.setString(5, moduleType)
                ps.setString(6, dependencies)
                ps.executeUpdate()
                val keys = ps.generatedKeys
                return if (keys.next()) keys.getInt(1) else -1
            }
        }
    }

    fun getAllModules(): List<ModuleRecord> {
        synchronized(lock) {
            val conn = getConnection()
            val rs = conn.createStatement().executeQuery("SELECT * FROM modules ORDER BY name")
            val result = mutableListOf<ModuleRecord>()
            while (rs.next()) {
                result.add(ModuleRecord(
                    id = rs.getInt("id"), repoId = rs.getInt("repo_id"),
                    name = rs.getString("name"), path = rs.getString("path"),
                    pluginXml = rs.getString("plugin_xml"), moduleType = rs.getString("module_type"),
                    dependencies = rs.getString("dependencies"), summary = rs.getString("summary"),
                    summaryAt = rs.getString("summary_at"), fileCount = rs.getInt("file_count")
                ))
            }
            return result
        }
    }

    fun getModuleByName(name: String): ModuleRecord? {
        synchronized(lock) {
            val conn = getConnection()
            conn.prepareStatement("SELECT * FROM modules WHERE name = ?").use { ps ->
                ps.setString(1, name)
                val rs = ps.executeQuery()
                return if (rs.next()) ModuleRecord(
                    id = rs.getInt("id"), repoId = rs.getInt("repo_id"),
                    name = rs.getString("name"), path = rs.getString("path"),
                    pluginXml = rs.getString("plugin_xml"), moduleType = rs.getString("module_type"),
                    dependencies = rs.getString("dependencies"), summary = rs.getString("summary"),
                    summaryAt = rs.getString("summary_at"), fileCount = rs.getInt("file_count")
                ) else null
            }
        }
    }

    fun getModulesByRepo(repoId: Int): List<ModuleRecord> {
        synchronized(lock) {
            val conn = getConnection()
            conn.prepareStatement("SELECT * FROM modules WHERE repo_id = ? ORDER BY name").use { ps ->
                ps.setInt(1, repoId)
                val rs = ps.executeQuery()
                val result = mutableListOf<ModuleRecord>()
                while (rs.next()) {
                    result.add(ModuleRecord(
                        id = rs.getInt("id"), repoId = rs.getInt("repo_id"),
                        name = rs.getString("name"), path = rs.getString("path"),
                        pluginXml = rs.getString("plugin_xml"), moduleType = rs.getString("module_type"),
                        dependencies = rs.getString("dependencies"), summary = rs.getString("summary"),
                        summaryAt = rs.getString("summary_at"), fileCount = rs.getInt("file_count")
                    ))
                }
                return result
            }
        }
    }

    fun getModuleCount(): Int {
        synchronized(lock) {
            val conn = getConnection()
            val rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM modules")
            return if (rs.next()) rs.getInt(1) else 0
        }
    }

    fun updateModuleSummary(moduleId: Int, summary: String) {
        synchronized(lock) {
            val conn = getConnection()
            conn.prepareStatement("UPDATE modules SET summary = ?, summary_at = ? WHERE id = ?").use { ps ->
                ps.setString(1, summary)
                ps.setString(2, Instant.now().toString())
                ps.setInt(3, moduleId)
                ps.executeUpdate()
            }
        }
    }

    fun updateModuleFileCount(moduleId: Int, count: Int) {
        synchronized(lock) {
            val conn = getConnection()
            conn.prepareStatement("UPDATE modules SET file_count = ? WHERE id = ?").use { ps ->
                ps.setInt(1, count)
                ps.setInt(2, moduleId)
                ps.executeUpdate()
            }
        }
    }

    fun getFilesByModule(moduleId: Int): List<FileRecord> {
        synchronized(lock) {
            val conn = getConnection()
            conn.prepareStatement("SELECT * FROM files WHERE module_id = ?").use { ps ->
                ps.setInt(1, moduleId)
                ps.executeQuery().use { rs ->
                    return rs.toFileRecords()
                }
            }
        }
    }

    fun getFileCountByRepo(repoId: Int): Int {
        synchronized(lock) {
            val conn = getConnection()
            conn.prepareStatement("SELECT COUNT(*) FROM files WHERE repo_id = ?").use { ps ->
                ps.setInt(1, repoId)
                val rs = ps.executeQuery()
                return if (rs.next()) rs.getInt(1) else 0
            }
        }
    }

    // ── Extension Points CRUD ─────────────────────────────────

    fun insertExtensionPoint(moduleId: Int, qualifiedName: String, interfaceFqn: String?, beanClass: String?, area: String?, description: String?): Int {
        synchronized(lock) {
            val conn = getConnection()
            val sql = """INSERT OR IGNORE INTO extension_points (module_id, qualified_name, interface_fqn, bean_class, area, description)
                VALUES (?, ?, ?, ?, ?, ?)"""
            conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { ps ->
                ps.setInt(1, moduleId)
                ps.setString(2, qualifiedName)
                ps.setString(3, interfaceFqn)
                ps.setString(4, beanClass)
                ps.setString(5, area)
                ps.setString(6, description)
                ps.executeUpdate()
                val keys = ps.generatedKeys
                return if (keys.next()) keys.getInt(1) else -1
            }
        }
    }

    fun insertEpImplementation(extensionPointId: Int, moduleId: Int, implementationFqn: String, pluginXmlPath: String?) {
        synchronized(lock) {
            val conn = getConnection()
            val sql = """INSERT INTO ep_implementations (extension_point_id, module_id, implementation_fqn, plugin_xml_path)
                VALUES (?, ?, ?, ?)"""
            conn.prepareStatement(sql).use { ps ->
                ps.setInt(1, extensionPointId)
                ps.setInt(2, moduleId)
                ps.setString(3, implementationFqn)
                ps.setString(4, pluginXmlPath)
                ps.executeUpdate()
            }
        }
    }

    fun getAllExtensionPoints(): List<ExtensionPointRecord> {
        synchronized(lock) {
            val conn = getConnection()
            val rs = conn.createStatement().executeQuery("SELECT * FROM extension_points ORDER BY qualified_name")
            val result = mutableListOf<ExtensionPointRecord>()
            while (rs.next()) {
                result.add(ExtensionPointRecord(
                    id = rs.getInt("id"), moduleId = rs.getInt("module_id"),
                    qualifiedName = rs.getString("qualified_name"),
                    interfaceFqn = rs.getString("interface_fqn"),
                    beanClass = rs.getString("bean_class"),
                    area = rs.getString("area"),
                    description = rs.getString("description")
                ))
            }
            return result
        }
    }

    fun getExtensionPointsByModule(moduleId: Int): List<ExtensionPointRecord> {
        synchronized(lock) {
            val conn = getConnection()
            conn.prepareStatement("SELECT * FROM extension_points WHERE module_id = ?").use { ps ->
                ps.setInt(1, moduleId)
                val rs = ps.executeQuery()
                val result = mutableListOf<ExtensionPointRecord>()
                while (rs.next()) {
                    result.add(ExtensionPointRecord(
                        id = rs.getInt("id"), moduleId = rs.getInt("module_id"),
                        qualifiedName = rs.getString("qualified_name"),
                        interfaceFqn = rs.getString("interface_fqn"),
                        beanClass = rs.getString("bean_class"),
                        area = rs.getString("area"),
                        description = rs.getString("description")
                    ))
                }
                return result
            }
        }
    }

    fun getImplementationCount(extensionPointId: Int): Int {
        synchronized(lock) {
            val conn = getConnection()
            conn.prepareStatement("SELECT COUNT(*) FROM ep_implementations WHERE extension_point_id = ?").use { ps ->
                ps.setInt(1, extensionPointId)
                val rs = ps.executeQuery()
                return if (rs.next()) rs.getInt(1) else 0
            }
        }
    }

    // ── FTS5 / search queries ─────────────────────────────────

    fun searchChunksFts(query: String, limit: Int = 50): List<ChunkRecord> {
        synchronized(lock) {
            try {
                val conn = getConnection()
                val sql = """SELECT c.* FROM chunks_fts f JOIN chunks c ON f.rowid = c.id WHERE chunks_fts MATCH ? LIMIT ?"""
                conn.prepareStatement(sql).use { ps ->
                    ps.setString(1, query)
                    ps.setInt(2, limit)
                    ps.executeQuery().use { rs ->
                        return rs.toChunkRecords()
                    }
                }
            } catch (e: Exception) {
                // FTS5 may not be available, fallback to LIKE
                return searchChunksLike(query, limit)
            }
        }
    }

    private fun searchChunksLike(query: String, limit: Int): List<ChunkRecord> {
        val conn = getConnection()
        conn.prepareStatement("SELECT * FROM chunks WHERE content LIKE ? LIMIT ?").use { ps ->
            ps.setString(1, "%$query%")
            ps.setInt(2, limit)
            ps.executeQuery().use { rs ->
                return rs.toChunkRecords()
            }
        }
    }

    fun getChunksWithEmbeddingsByModules(moduleIds: List<Int>, limit: Int = 5000): List<ChunkRecord> {
        if (moduleIds.isEmpty()) return emptyList()
        synchronized(lock) {
            val conn = getConnection()
            val placeholders = moduleIds.joinToString(",") { "?" }
            // Distribute limit evenly across modules for balanced sampling
            val perModuleLimit = (limit / moduleIds.size).coerceAtLeast(100)
            val queries = moduleIds.mapIndexed { i, id ->
                "SELECT c.* FROM chunks c JOIN files f ON c.file_id = f.id WHERE f.module_id = ? AND c.embedding IS NOT NULL AND length(c.embedding) > 0 ORDER BY c.id DESC LIMIT $perModuleLimit"
            }
            val sql = queries.joinToString(" UNION ALL ")
            conn.prepareStatement(sql).use { ps ->
                moduleIds.forEachIndexed { i, _ ->
                    ps.setInt(i + 1, moduleIds[i])
                }
                ps.executeQuery().use { rs ->
                    return rs.toChunkRecords()
                }
            }
        }
    }

    fun getChunksWithoutEmbeddingsByModule(moduleId: Int, limit: Int): List<ChunkRecord> {
        synchronized(lock) {
            val conn = getConnection()
            // Exclude chunks with NULL embedding AND chunks with zero-length marker (failed)
            val sql = """SELECT c.* FROM chunks c JOIN files f ON c.file_id = f.id
                WHERE f.module_id = ? AND c.embedding IS NULL AND length(c.content) > 0 LIMIT ?"""
            conn.prepareStatement(sql).use { ps ->
                ps.setInt(1, moduleId)
                ps.setInt(2, limit)
                ps.executeQuery().use { rs ->
                    return rs.toChunkRecords()
                }
            }
        }
    }

    fun getChunkById(chunkId: Int): ChunkRecord? {
        synchronized(lock) {
            val conn = getConnection()
            conn.prepareStatement("SELECT * FROM chunks WHERE id = ?").use { ps ->
                ps.setInt(1, chunkId)
                ps.executeQuery().use { rs ->
                    val records = rs.toChunkRecords()
                    return records.firstOrNull()
                }
            }
        }
    }

    // ── File → Module assignment ────────────────────────────────

    /**
     * Counts files that have no module_id assigned.
     */
    fun countFilesWithoutModule(): Int {
        synchronized(lock) {
            val conn = getConnection()
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT COUNT(*) FROM files WHERE module_id IS NULL").use { rs ->
                    return if (rs.next()) rs.getInt(1) else 0
                }
            }
        }
    }

    /**
     * Assigns module_id to files based on their relative path matching module names.
     * For each module, finds files whose relative_path starts with the module's name.
     * Sorted by name length DESC so more-specific modules win
     * (e.g. platform/core-api matched before platform).
     */
    fun assignFilesToModules(repoPathPrefix: String) {
        synchronized(lock) {
            val conn = getConnection()
            // Get all modules with their names (relative paths)
            val modules = mutableListOf<Pair<Int, String>>() // (module_id, module_name)
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT id, name FROM modules ORDER BY length(name) DESC").use { rs ->
                    while (rs.next()) {
                        modules.add(rs.getInt("id") to rs.getString("name"))
                    }
                }
            }

            // Assign files in batches — use module name for LIKE against relative_path
            conn.autoCommit = false
            try {
                conn.prepareStatement(
                    "UPDATE files SET module_id = ? WHERE module_id IS NULL AND relative_path LIKE ?"
                ).use { ps ->
                    for ((moduleId, moduleName) in modules) {
                        ps.setInt(1, moduleId)
                        ps.setString(2, "$moduleName/%")
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }

                // Update file_count on each module from actual assigned files
                conn.createStatement().use { stmt ->
                    stmt.executeUpdate("""
                        UPDATE modules SET file_count = (
                            SELECT COUNT(*) FROM files WHERE files.module_id = modules.id
                        )
                    """.trimIndent())
                }

                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    // ── Delete operations ─────────────────────────────────────

    fun deleteFilesByRepo(repoId: Int) {
        synchronized(lock) {
            val conn = getConnection()
            conn.prepareStatement("DELETE FROM files WHERE repo_id = ?").use { ps ->
                ps.setInt(1, repoId)
                ps.executeUpdate()
            }
        }
    }

    fun deleteFile(path: String) {
        synchronized(lock) {
            val conn = getConnection()
            conn.prepareStatement("DELETE FROM files WHERE path = ?").use { ps ->
                ps.setString(1, path)
                ps.executeUpdate()
            }
        }
    }

    // ── Bulk operations ──────────────────────────────────────

    fun clearAll() {
        synchronized(lock) {
            val conn = getConnection()
            conn.createStatement().use { stmt ->
                stmt.execute("DELETE FROM chunks")
                stmt.execute("DELETE FROM evidence")
                stmt.execute("DELETE FROM tasks")
                stmt.execute("DELETE FROM files")
                stmt.execute("DELETE FROM project_meta")
            }
        }
    }

    // ── ResultSet mapping helpers ────────────────────────────

    private fun ResultSet.toFileRecords(): List<FileRecord> {
        val records = mutableListOf<FileRecord>()
        while (next()) {
            records.add(
                FileRecord(
                    id = getInt("id"),
                    path = getString("path"),
                    relativePath = getString("relative_path"),
                    language = getString("language"),
                    sizeBytes = getLong("size_bytes").let { if (wasNull()) null else it },
                    lineCount = getInt("line_count").let { if (wasNull()) null else it },
                    sha256 = getString("sha256"),
                    category = getString("category"),
                    scannedAt = getString("scanned_at")
                )
            )
        }
        return records
    }

    private fun ResultSet.toChunkRecords(): List<ChunkRecord> {
        val records = mutableListOf<ChunkRecord>()
        while (next()) {
            records.add(
                ChunkRecord(
                    id = getInt("id"),
                    fileId = getInt("file_id"),
                    content = getString("content"),
                    startLine = getInt("start_line").let { if (wasNull()) null else it },
                    endLine = getInt("end_line").let { if (wasNull()) null else it },
                    chunkType = getString("chunk_type"),
                    symbolName = getString("symbol_name"),
                    language = getString("language"),
                    embedding = getBytes("embedding"),
                    createdAt = getString("created_at")
                )
            )
        }
        return records
    }

    private fun ResultSet.toEvidenceRecords(): List<EvidenceRecord> {
        val records = mutableListOf<EvidenceRecord>()
        while (next()) {
            records.add(
                EvidenceRecord(
                    id = getInt("id"),
                    taskId = getString("task_id"),
                    category = getString("category"),
                    key = getString("key"),
                    value = getString("value"),
                    confidence = getDouble("confidence"),
                    sourceFile = getString("source_file"),
                    createdAt = getString("created_at")
                )
            )
        }
        return records
    }
}
