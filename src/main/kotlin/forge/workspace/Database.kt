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

// ── Index data classes ────────────────────────────────────────

data class EntityRecord(
    val id: Int,
    val fileId: Int,
    val moduleId: Int?,
    val entityType: String,
    val name: String,
    val qualifiedName: String?,
    val parentEntityId: Int?,
    val language: String?,
    val visibility: String?,
    val signature: String?,
    val startLine: Int,
    val endLine: Int,
    val sha256: String?,
    val filePath: String? = null
)

data class EntityRelationshipRecord(
    val id: Int,
    val sourceEntityId: Int,
    val targetEntityId: Int?,
    val targetName: String,
    val relationship: String,
    val confidence: Double,
    val sourceLine: Int?
)

data class LineIndexRecord(
    val id: Int,
    val fileId: Int,
    val lineNum: Int,
    val entityId: Int?,
    val lineType: String?
)

data class DependencyEdge(
    val id: Int,
    val sourceModuleId: Int?,
    val targetModuleId: Int?,
    val sourcePath: String,
    val targetPath: String,
    val depType: String,
    val weight: Int
)

data class FileClassification(
    val id: Int,
    val fileId: Int,
    val primaryClass: String,
    val secondaryClass: String?,
    val frameworkHint: String?,
    val entryPoint: Boolean,
    val generated: Boolean
)

data class IndexMetadataRecord(
    val id: Int,
    val indexType: String,
    val status: String,
    val startedAt: String?,
    val completedAt: String?,
    val durationMs: Long?,
    val filesProcessed: Int,
    val entitiesFound: Int,
    val relationshipsFound: Int,
    val errors: String?,
    val commitSha: String?,
    val trigger: String?
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
        migrateEvidenceKeys()
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
                created_at  TEXT,
                UNIQUE(task_id, category, key, value)
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

        // ── Model Evolution tables ──────────────────────────────
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS training_data (
                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id      TEXT,
                task_id         TEXT REFERENCES tasks(id),
                input_prompt    TEXT NOT NULL,
                system_prompt   TEXT,
                output_response TEXT NOT NULL,
                task_type       TEXT,
                model_used      TEXT,
                quality_score   REAL DEFAULT 0.0,
                user_rating     INTEGER,
                is_validated    INTEGER DEFAULT 0,
                is_exported     INTEGER DEFAULT 0,
                tags            TEXT,
                created_at      TEXT,
                validated_at    TEXT
            )
        """.trimIndent())

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS model_registry (
                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                name            TEXT UNIQUE NOT NULL,
                base_model      TEXT NOT NULL,
                version         TEXT NOT NULL,
                status          TEXT DEFAULT 'draft',
                modelfile_path  TEXT,
                adapter_path    TEXT,
                training_run_id TEXT,
                metrics_json    TEXT,
                created_at      TEXT,
                activated_at    TEXT
            )
        """.trimIndent())

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS training_runs (
                id              TEXT PRIMARY KEY,
                model_name      TEXT,
                dataset_version TEXT NOT NULL,
                dataset_size    INTEGER,
                method          TEXT,
                hyperparams     TEXT,
                eval_metrics    TEXT,
                status          TEXT DEFAULT 'planned',
                started_at      TEXT,
                completed_at    TEXT
            )
        """.trimIndent())

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS dataset_exports (
                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                version         TEXT NOT NULL,
                format          TEXT NOT NULL,
                record_count    INTEGER,
                file_path       TEXT,
                quality_min     REAL,
                created_at      TEXT
            )
        """.trimIndent())

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS prompt_partitions (
                id             TEXT PRIMARY KEY,
                request_id     TEXT NOT NULL,
                task_id        TEXT REFERENCES tasks(id),
                semantic_label TEXT,
                archetype      TEXT,
                task_type      TEXT,
                sub_prompt     TEXT,
                depends_on     TEXT,
                status         TEXT,
                result_summary TEXT,
                duration_ms    INTEGER,
                created_at     TEXT DEFAULT (datetime('now'))
            )
        """.trimIndent())

        // ── Project Index tables ──────────────────────────────────
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS entities (
                id               INTEGER PRIMARY KEY AUTOINCREMENT,
                file_id          INTEGER NOT NULL REFERENCES files(id) ON DELETE CASCADE,
                module_id        INTEGER REFERENCES modules(id),
                entity_type      TEXT NOT NULL,
                name             TEXT NOT NULL,
                qualified_name   TEXT,
                parent_entity_id INTEGER REFERENCES entities(id),
                language         TEXT,
                visibility       TEXT,
                signature        TEXT,
                start_line       INTEGER NOT NULL,
                end_line         INTEGER NOT NULL,
                sha256           TEXT,
                created_at       TEXT DEFAULT (datetime('now')),
                updated_at       TEXT
            )
        """.trimIndent())

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS entity_relationships (
                id               INTEGER PRIMARY KEY AUTOINCREMENT,
                source_entity_id INTEGER NOT NULL REFERENCES entities(id) ON DELETE CASCADE,
                target_entity_id INTEGER REFERENCES entities(id) ON DELETE SET NULL,
                target_name      TEXT NOT NULL,
                relationship     TEXT NOT NULL,
                confidence       REAL DEFAULT 1.0,
                source_line      INTEGER,
                created_at       TEXT DEFAULT (datetime('now'))
            )
        """.trimIndent())

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS line_index (
                id        INTEGER PRIMARY KEY AUTOINCREMENT,
                file_id   INTEGER NOT NULL REFERENCES files(id) ON DELETE CASCADE,
                line_num  INTEGER NOT NULL,
                entity_id INTEGER REFERENCES entities(id) ON DELETE CASCADE,
                line_type TEXT,
                UNIQUE(file_id, line_num)
            )
        """.trimIndent())

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS dependency_graph (
                id               INTEGER PRIMARY KEY AUTOINCREMENT,
                source_module_id INTEGER REFERENCES modules(id) ON DELETE CASCADE,
                target_module_id INTEGER REFERENCES modules(id) ON DELETE SET NULL,
                source_path      TEXT NOT NULL,
                target_path      TEXT NOT NULL,
                dep_type         TEXT NOT NULL DEFAULT 'compile',
                weight           INTEGER DEFAULT 1,
                created_at       TEXT DEFAULT (datetime('now'))
            )
        """.trimIndent())

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS file_classifications (
                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                file_id         INTEGER UNIQUE NOT NULL REFERENCES files(id) ON DELETE CASCADE,
                primary_class   TEXT NOT NULL,
                secondary_class TEXT,
                framework_hint  TEXT,
                entry_point     INTEGER DEFAULT 0,
                generated       INTEGER DEFAULT 0,
                created_at      TEXT DEFAULT (datetime('now'))
            )
        """.trimIndent())

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS index_metadata (
                id                  INTEGER PRIMARY KEY AUTOINCREMENT,
                index_type          TEXT NOT NULL,
                status              TEXT NOT NULL,
                started_at          TEXT,
                completed_at        TEXT,
                duration_ms         INTEGER,
                files_processed     INTEGER DEFAULT 0,
                entities_found      INTEGER DEFAULT 0,
                relationships_found INTEGER DEFAULT 0,
                errors              TEXT,
                commit_sha          TEXT,
                trigger_type        TEXT
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
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_pp_request_id     ON prompt_partitions(request_id)")
        // Model Evolution indexes
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_td_task_id        ON training_data(task_id)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_td_quality        ON training_data(quality_score)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_td_validated      ON training_data(is_validated)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_mr_status         ON model_registry(status)")
        // Project Index indexes
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_entities_file_id        ON entities(file_id)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_entities_module_id      ON entities(module_id)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_entities_type           ON entities(entity_type)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_entities_name           ON entities(name)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_entities_qualified_name ON entities(qualified_name)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_entities_parent_id      ON entities(parent_entity_id)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_er_source               ON entity_relationships(source_entity_id)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_er_target               ON entity_relationships(target_entity_id)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_er_rel_type             ON entity_relationships(relationship)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_er_tgt_name             ON entity_relationships(target_name)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_li_file_line            ON line_index(file_id, line_num)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_li_entity_id            ON line_index(entity_id)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_dg_source               ON dependency_graph(source_module_id)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_dg_target               ON dependency_graph(target_module_id)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_fc_class                ON file_classifications(primary_class)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_im_type                 ON index_metadata(index_type)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_im_status               ON index_metadata(status)")
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

    /**
     * Clear stale evidence that used non-unique keys (e.g. all sub-services stored
     * under key "sub_service"). Evidence schema v2 uses path-qualified keys like
     * "sub_service:microservices/alerts". Clearing forces re-collection on next run.
     */
    private fun migrateEvidenceKeys() {
        synchronized(lock) {
            val conn = getConnection()
            val currentVersion = try {
                conn.prepareStatement("SELECT value FROM project_meta WHERE key = ?").use { ps ->
                    ps.setString(1, "evidence_schema_version")
                    ps.executeQuery().use { rs ->
                        if (rs.next()) rs.getString("value") else null
                    }
                }
            } catch (_: Exception) { null }

            if (currentVersion != "2") {
                conn.createStatement().use { stmt ->
                    stmt.execute("DELETE FROM evidence")
                }
                conn.prepareStatement(
                    "INSERT OR REPLACE INTO project_meta (key, value, updated_at) VALUES (?, ?, ?)"
                ).use { ps ->
                    ps.setString(1, "evidence_schema_version")
                    ps.setString(2, "2")
                    ps.setString(3, java.time.Instant.now().toString())
                    ps.executeUpdate()
                }
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

    /**
     * Returns distinct top-level directories from indexed files.
     * E.g., for paths like "src/main/java/Foo.java", "docs/README.md" → ["src", "docs"]
     */
    fun getTopLevelDirectories(limit: Int = 40): List<String> {
        synchronized(lock) {
            val conn = getConnection()
            val result = mutableListOf<String>()
            conn.createStatement().use { stmt ->
                stmt.executeQuery("""
                    SELECT DISTINCT
                        CASE
                            WHEN INSTR(relative_path, '/') > 0 THEN SUBSTR(relative_path, 1, INSTR(relative_path, '/') - 1)
                            ELSE relative_path
                        END AS top_dir
                    FROM files
                    ORDER BY top_dir
                    LIMIT $limit
                """).use { rs ->
                    while (rs.next()) {
                        result.add(rs.getString("top_dir"))
                    }
                }
            }
            return result
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
                stmt.executeQuery("SELECT * FROM chunks WHERE embedding IS NOT NULL AND length(embedding) > 0").use { rs ->
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
                INSERT OR IGNORE INTO evidence (task_id, category, key, value, confidence, source_file, created_at)
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

    fun getDistinctModuleTypeCount(): Int {
        synchronized(lock) {
            val conn = getConnection()
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT COUNT(DISTINCT module_type) FROM modules").use { rs ->
                    return if (rs.next()) rs.getInt(1) else 0
                }
            }
        }
    }

    fun getEmbeddingCount(): Int {
        synchronized(lock) {
            val conn = getConnection()
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT COUNT(*) FROM chunks WHERE embedding IS NOT NULL AND length(embedding) > 0").use { rs ->
                    return if (rs.next()) rs.getInt(1) else 0
                }
            }
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
            val sql = """SELECT c.* FROM chunks c JOIN files f ON c.file_id = f.id
                WHERE f.module_id IN ($placeholders) AND c.embedding IS NOT NULL
                AND length(c.embedding) > 0
                LIMIT ?"""
            conn.prepareStatement(sql).use { ps ->
                moduleIds.forEachIndexed { i, id -> ps.setInt(i + 1, id) }
                ps.setInt(moduleIds.size + 1, limit)
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

    // ── Training Data CRUD (Model Evolution) ─────────────────

    fun insertTrainingData(
        sessionId: String?,
        taskId: String?,
        inputPrompt: String,
        systemPrompt: String?,
        outputResponse: String,
        taskType: String?,
        modelUsed: String?,
        qualityScore: Double = 0.0,
        isValidated: Boolean = false
    ): Int {
        synchronized(lock) {
            val conn = getConnection()
            val sql = """
                INSERT INTO training_data
                (session_id, task_id, input_prompt, system_prompt, output_response, task_type, model_used, quality_score, is_validated, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
            conn.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS).use { ps ->
                ps.setString(1, sessionId)
                ps.setString(2, taskId)
                ps.setString(3, inputPrompt)
                ps.setString(4, systemPrompt)
                ps.setString(5, outputResponse)
                ps.setString(6, taskType)
                ps.setString(7, modelUsed)
                ps.setDouble(8, qualityScore)
                ps.setInt(9, if (isValidated) 1 else 0)
                ps.setString(10, Instant.now().toString())
                ps.executeUpdate()
                ps.generatedKeys.use { rs ->
                    return if (rs.next()) rs.getInt(1) else -1
                }
            }
        }
    }

    fun getTrainingDataCount(): Int {
        synchronized(lock) {
            val conn = getConnection()
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT COUNT(*) FROM training_data").use { rs ->
                    return if (rs.next()) rs.getInt(1) else 0
                }
            }
        }
    }

    fun getValidatedTrainingDataCount(): Int {
        synchronized(lock) {
            val conn = getConnection()
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT COUNT(*) FROM training_data WHERE is_validated = 1").use { rs ->
                    return if (rs.next()) rs.getInt(1) else 0
                }
            }
        }
    }

    fun updateTrainingDataRating(id: Int, rating: Int) {
        synchronized(lock) {
            val conn = getConnection()
            conn.prepareStatement("UPDATE training_data SET user_rating = ?, is_validated = 1, validated_at = ? WHERE id = ?").use { ps ->
                ps.setInt(1, rating)
                ps.setString(2, Instant.now().toString())
                ps.setInt(3, id)
                ps.executeUpdate()
            }
        }
    }

    fun getTrainingDataForExport(minQuality: Double, limit: Int = 10000): List<Map<String, Any?>> {
        synchronized(lock) {
            val conn = getConnection()
            val results = mutableListOf<Map<String, Any?>>()
            conn.prepareStatement("""
                SELECT id, input_prompt, system_prompt, output_response, task_type, model_used, quality_score, user_rating
                FROM training_data
                WHERE is_validated = 1 AND quality_score >= ? AND is_exported = 0
                ORDER BY quality_score DESC
                LIMIT ?
            """.trimIndent()).use { ps ->
                ps.setDouble(1, minQuality)
                ps.setInt(2, limit)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        results.add(mapOf(
                            "id" to rs.getInt("id"),
                            "input" to rs.getString("input_prompt"),
                            "system" to rs.getString("system_prompt"),
                            "output" to rs.getString("output_response"),
                            "task_type" to rs.getString("task_type"),
                            "model" to rs.getString("model_used"),
                            "quality" to rs.getDouble("quality_score"),
                            "rating" to rs.getInt("user_rating")
                        ))
                    }
                }
            }
            return results
        }
    }

    fun getTrainingDataPaginated(page: Int = 0, pageSize: Int = 20): List<Map<String, Any?>> {
        synchronized(lock) {
            val conn = getConnection()
            val results = mutableListOf<Map<String, Any?>>()
            conn.prepareStatement("""
                SELECT id, input_prompt, output_response, task_type, model_used,
                       quality_score, user_rating, is_validated, created_at
                FROM training_data
                ORDER BY created_at DESC
                LIMIT ? OFFSET ?
            """.trimIndent()).use { ps ->
                ps.setInt(1, pageSize)
                ps.setInt(2, page * pageSize)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        results.add(mapOf(
                            "id" to rs.getInt("id"),
                            "input" to rs.getString("input_prompt"),
                            "output" to rs.getString("output_response"),
                            "task_type" to rs.getString("task_type"),
                            "model_used" to rs.getString("model_used"),
                            "quality" to rs.getDouble("quality_score"),
                            "user_rating" to rs.getObject("user_rating"),
                            "is_validated" to (rs.getInt("is_validated") == 1),
                            "created_at" to rs.getString("created_at")
                        ))
                    }
                }
            }
            return results
        }
    }

    fun markTrainingDataExported(ids: List<Int>) {
        if (ids.isEmpty()) return
        synchronized(lock) {
            val conn = getConnection()
            val placeholders = ids.joinToString(",") { "?" }
            conn.prepareStatement("UPDATE training_data SET is_exported = 1 WHERE id IN ($placeholders)").use { ps ->
                ids.forEachIndexed { idx, id -> ps.setInt(idx + 1, id) }
                ps.executeUpdate()
            }
        }
    }

    // ── Model Registry CRUD ──────────────────────────────────

    fun insertModelRegistryEntry(
        name: String,
        baseModel: String,
        version: String,
        status: String = "draft",
        modelfilePath: String? = null
    ): Int {
        synchronized(lock) {
            val conn = getConnection()
            val sql = """
                INSERT OR REPLACE INTO model_registry (name, base_model, version, status, modelfile_path, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent()
            conn.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS).use { ps ->
                ps.setString(1, name)
                ps.setString(2, baseModel)
                ps.setString(3, version)
                ps.setString(4, status)
                ps.setString(5, modelfilePath)
                ps.setString(6, Instant.now().toString())
                ps.executeUpdate()
                ps.generatedKeys.use { rs ->
                    return if (rs.next()) rs.getInt(1) else -1
                }
            }
        }
    }

    fun getActiveModel(): Map<String, String>? {
        synchronized(lock) {
            val conn = getConnection()
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT name, base_model, version, activated_at FROM model_registry WHERE status = 'active' LIMIT 1").use { rs ->
                    if (rs.next()) {
                        return mapOf(
                            "name" to rs.getString("name"),
                            "base_model" to rs.getString("base_model"),
                            "version" to rs.getString("version"),
                            "activated_at" to (rs.getString("activated_at") ?: "")
                        )
                    }
                    return null
                }
            }
        }
    }

    fun activateModel(name: String) {
        synchronized(lock) {
            val conn = getConnection()
            // Retire current active model
            conn.createStatement().use { stmt ->
                stmt.executeUpdate("UPDATE model_registry SET status = 'retired' WHERE status = 'active'")
            }
            // Activate the new one
            conn.prepareStatement("UPDATE model_registry SET status = 'active', activated_at = ? WHERE name = ?").use { ps ->
                ps.setString(1, Instant.now().toString())
                ps.setString(2, name)
                ps.executeUpdate()
            }
        }
    }

    fun rollbackModel(): String? {
        synchronized(lock) {
            val conn = getConnection()
            // Find the most recently retired model
            val retired = conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT name FROM model_registry WHERE status = 'retired' ORDER BY activated_at DESC LIMIT 1").use { rs ->
                    if (rs.next()) rs.getString("name") else null
                }
            }
            if (retired != null) {
                // Mark current active as rollback
                conn.createStatement().use { stmt ->
                    stmt.executeUpdate("UPDATE model_registry SET status = 'rollback' WHERE status = 'active'")
                }
                // Reactivate the retired one
                conn.prepareStatement("UPDATE model_registry SET status = 'active', activated_at = ? WHERE name = ?").use { ps ->
                    ps.setString(1, Instant.now().toString())
                    ps.setString(2, retired)
                    ps.executeUpdate()
                }
            }
            return retired
        }
    }

    fun insertDatasetExport(version: String, format: String, recordCount: Int, filePath: String, qualityMin: Double) {
        synchronized(lock) {
            val conn = getConnection()
            conn.prepareStatement("""
                INSERT INTO dataset_exports (version, format, record_count, file_path, quality_min, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent()).use { ps ->
                ps.setString(1, version)
                ps.setString(2, format)
                ps.setInt(3, recordCount)
                ps.setString(4, filePath)
                ps.setDouble(5, qualityMin)
                ps.setString(6, Instant.now().toString())
                ps.executeUpdate()
            }
        }
    }

    fun getModelRegistryEntries(): List<Map<String, Any?>> {
        synchronized(lock) {
            val conn = getConnection()
            val results = mutableListOf<Map<String, Any?>>()
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT * FROM model_registry ORDER BY created_at DESC").use { rs ->
                    while (rs.next()) {
                        results.add(mapOf(
                            "id" to rs.getInt("id"),
                            "name" to rs.getString("name"),
                            "base_model" to rs.getString("base_model"),
                            "version" to rs.getString("version"),
                            "status" to rs.getString("status"),
                            "modelfile_path" to rs.getString("modelfile_path"),
                            "adapter_path" to rs.getString("adapter_path"),
                            "metrics_json" to rs.getString("metrics_json"),
                            "created_at" to rs.getString("created_at"),
                            "activated_at" to rs.getString("activated_at")
                        ))
                    }
                }
            }
            return results
        }
    }

    // ── Entity CRUD ──────────────────────────────────────────────

    fun insertEntity(
        fileId: Int,
        moduleId: Int?,
        entityType: String,
        name: String,
        qualifiedName: String?,
        parentEntityId: Int?,
        language: String?,
        visibility: String?,
        signature: String?,
        startLine: Int,
        endLine: Int,
        sha256: String?
    ): Int {
        synchronized(lock) {
            val conn = getConnection()
            val sql = """
                INSERT INTO entities (file_id, module_id, entity_type, name, qualified_name, parent_entity_id, language, visibility, signature, start_line, end_line, sha256, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
            conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { ps ->
                ps.setInt(1, fileId)
                if (moduleId != null) ps.setInt(2, moduleId) else ps.setNull(2, java.sql.Types.INTEGER)
                ps.setString(3, entityType)
                ps.setString(4, name)
                ps.setString(5, qualifiedName)
                if (parentEntityId != null) ps.setInt(6, parentEntityId) else ps.setNull(6, java.sql.Types.INTEGER)
                ps.setString(7, language)
                ps.setString(8, visibility)
                ps.setString(9, signature)
                ps.setInt(10, startLine)
                ps.setInt(11, endLine)
                ps.setString(12, sha256)
                ps.setString(13, Instant.now().toString())
                ps.executeUpdate()
                ps.generatedKeys.use { rs ->
                    return if (rs.next()) rs.getInt(1) else -1
                }
            }
        }
    }

    fun insertEntitiesBatch(entities: List<EntityRecord>) {
        if (entities.isEmpty()) return
        synchronized(lock) {
            val conn = getConnection()
            val wasAutoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                val sql = """
                    INSERT INTO entities (file_id, module_id, entity_type, name, qualified_name, parent_entity_id, language, visibility, signature, start_line, end_line, sha256, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
                conn.prepareStatement(sql).use { ps ->
                    for (e in entities) {
                        ps.setInt(1, e.fileId)
                        if (e.moduleId != null) ps.setInt(2, e.moduleId) else ps.setNull(2, java.sql.Types.INTEGER)
                        ps.setString(3, e.entityType)
                        ps.setString(4, e.name)
                        ps.setString(5, e.qualifiedName)
                        if (e.parentEntityId != null) ps.setInt(6, e.parentEntityId) else ps.setNull(6, java.sql.Types.INTEGER)
                        ps.setString(7, e.language)
                        ps.setString(8, e.visibility)
                        ps.setString(9, e.signature)
                        ps.setInt(10, e.startLine)
                        ps.setInt(11, e.endLine)
                        ps.setString(12, e.sha256)
                        ps.setString(13, Instant.now().toString())
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

    fun getEntitiesByFile(fileId: Int): List<EntityRecord> {
        synchronized(lock) {
            val conn = getConnection()
            conn.prepareStatement("SELECT * FROM entities WHERE file_id = ?").use { ps ->
                ps.setInt(1, fileId)
                ps.executeQuery().use { rs ->
                    return rs.toEntityRecords()
                }
            }
        }
    }

    fun getEntitiesByModule(moduleId: Int): List<EntityRecord> {
        synchronized(lock) {
            val conn = getConnection()
            conn.prepareStatement("SELECT * FROM entities WHERE module_id = ?").use { ps ->
                ps.setInt(1, moduleId)
                ps.executeQuery().use { rs ->
                    return rs.toEntityRecords()
                }
            }
        }
    }

    fun getEntitiesByType(entityType: String, limit: Int = 1000): List<EntityRecord> {
        synchronized(lock) {
            val conn = getConnection()
            conn.prepareStatement("""
                SELECT e.*, f.relative_path AS file_path
                FROM entities e
                LEFT JOIN files f ON e.file_id = f.id
                WHERE e.entity_type = ?
                LIMIT ?
            """.trimIndent()).use { ps ->
                ps.setString(1, entityType)
                ps.setInt(2, limit)
                ps.executeQuery().use { rs ->
                    return rs.toEntityRecordsWithPath()
                }
            }
        }
    }

    fun getEntityByName(name: String): List<EntityRecord> {
        synchronized(lock) {
            val conn = getConnection()
            conn.prepareStatement("SELECT * FROM entities WHERE name = ?").use { ps ->
                ps.setString(1, name)
                ps.executeQuery().use { rs ->
                    return rs.toEntityRecords()
                }
            }
        }
    }

    fun getEntityByQualifiedName(qualifiedName: String): EntityRecord? {
        synchronized(lock) {
            val conn = getConnection()
            conn.prepareStatement("SELECT * FROM entities WHERE qualified_name = ?").use { ps ->
                ps.setString(1, qualifiedName)
                ps.executeQuery().use { rs ->
                    return rs.toEntityRecords().firstOrNull()
                }
            }
        }
    }

    fun getEntityById(id: Int): EntityRecord? {
        synchronized(lock) {
            val conn = getConnection()
            conn.prepareStatement("SELECT * FROM entities WHERE id = ?").use { ps ->
                ps.setInt(1, id)
                ps.executeQuery().use { rs ->
                    return rs.toEntityRecords().firstOrNull()
                }
            }
        }
    }

    fun searchEntities(query: String, limit: Int = 50): List<EntityRecord> {
        synchronized(lock) {
            val conn = getConnection()
            conn.prepareStatement("""
                SELECT e.*, f.relative_path AS file_path
                FROM entities e
                LEFT JOIN files f ON e.file_id = f.id
                WHERE e.name LIKE ? OR e.qualified_name LIKE ?
                LIMIT ?
            """.trimIndent()).use { ps ->
                ps.setString(1, "%$query%")
                ps.setString(2, "%$query%")
                ps.setInt(3, limit)
                ps.executeQuery().use { rs ->
                    return rs.toEntityRecordsWithPath()
                }
            }
        }
    }

    /**
     * Clears all index data (entities, relationships, line_index, dependency_graph)
     * before a full re-index to prevent orphaned records from stale file IDs.
     */
    fun clearAllIndexData() {
        synchronized(lock) {
            val conn = getConnection()
            conn.createStatement().use { stmt ->
                stmt.executeUpdate("DELETE FROM entity_relationships")
                stmt.executeUpdate("DELETE FROM entities")
                stmt.executeUpdate("DELETE FROM line_index")
                stmt.executeUpdate("DELETE FROM dependency_graph")
            }
        }
    }

    fun deleteEntitiesByFile(fileId: Int) {
        synchronized(lock) {
            val conn = getConnection()
            conn.prepareStatement("DELETE FROM entities WHERE file_id = ?").use { ps ->
                ps.setInt(1, fileId)
                ps.executeUpdate()
            }
        }
    }

    fun getEntityCount(): Int {
        synchronized(lock) {
            val conn = getConnection()
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT COUNT(*) FROM entities").use { rs ->
                    return if (rs.next()) rs.getInt(1) else 0
                }
            }
        }
    }

    // ── Relationship CRUD ────────────────────────────────────────

    fun insertRelationship(
        sourceEntityId: Int,
        targetEntityId: Int?,
        targetName: String,
        relationship: String,
        confidence: Double = 1.0,
        sourceLine: Int?
    ): Int {
        synchronized(lock) {
            val conn = getConnection()
            val sql = """
                INSERT INTO entity_relationships (source_entity_id, target_entity_id, target_name, relationship, confidence, source_line, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
            conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { ps ->
                ps.setInt(1, sourceEntityId)
                if (targetEntityId != null) ps.setInt(2, targetEntityId) else ps.setNull(2, java.sql.Types.INTEGER)
                ps.setString(3, targetName)
                ps.setString(4, relationship)
                ps.setDouble(5, confidence)
                if (sourceLine != null) ps.setInt(6, sourceLine) else ps.setNull(6, java.sql.Types.INTEGER)
                ps.setString(7, Instant.now().toString())
                ps.executeUpdate()
                ps.generatedKeys.use { rs ->
                    return if (rs.next()) rs.getInt(1) else -1
                }
            }
        }
    }

    fun insertRelationshipsBatch(relationships: List<EntityRelationshipRecord>) {
        if (relationships.isEmpty()) return
        synchronized(lock) {
            val conn = getConnection()
            val wasAutoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                val sql = """
                    INSERT INTO entity_relationships (source_entity_id, target_entity_id, target_name, relationship, confidence, source_line, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
                conn.prepareStatement(sql).use { ps ->
                    for (r in relationships) {
                        ps.setInt(1, r.sourceEntityId)
                        if (r.targetEntityId != null) ps.setInt(2, r.targetEntityId) else ps.setNull(2, java.sql.Types.INTEGER)
                        ps.setString(3, r.targetName)
                        ps.setString(4, r.relationship)
                        ps.setDouble(5, r.confidence)
                        if (r.sourceLine != null) ps.setInt(6, r.sourceLine) else ps.setNull(6, java.sql.Types.INTEGER)
                        ps.setString(7, Instant.now().toString())
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

    fun getRelationshipsBySource(entityId: Int): List<EntityRelationshipRecord> {
        synchronized(lock) {
            val conn = getConnection()
            conn.prepareStatement("SELECT * FROM entity_relationships WHERE source_entity_id = ?").use { ps ->
                ps.setInt(1, entityId)
                ps.executeQuery().use { rs ->
                    return rs.toRelationshipRecords()
                }
            }
        }
    }

    fun getRelationshipsByTarget(entityId: Int): List<EntityRelationshipRecord> {
        synchronized(lock) {
            val conn = getConnection()
            conn.prepareStatement("SELECT * FROM entity_relationships WHERE target_entity_id = ?").use { ps ->
                ps.setInt(1, entityId)
                ps.executeQuery().use { rs ->
                    return rs.toRelationshipRecords()
                }
            }
        }
    }

    fun getRelationshipsByType(relationship: String, limit: Int = 1000): List<EntityRelationshipRecord> {
        synchronized(lock) {
            val conn = getConnection()
            conn.prepareStatement("SELECT * FROM entity_relationships WHERE relationship = ? LIMIT ?").use { ps ->
                ps.setString(1, relationship)
                ps.setInt(2, limit)
                ps.executeQuery().use { rs ->
                    return rs.toRelationshipRecords()
                }
            }
        }
    }

    fun deleteRelationshipsByEntity(entityId: Int) {
        synchronized(lock) {
            val conn = getConnection()
            conn.prepareStatement("DELETE FROM entity_relationships WHERE source_entity_id = ? OR target_entity_id = ?").use { ps ->
                ps.setInt(1, entityId)
                ps.setInt(2, entityId)
                ps.executeUpdate()
            }
        }
    }

    fun getRelationshipCount(): Int {
        synchronized(lock) {
            val conn = getConnection()
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT COUNT(*) FROM entity_relationships").use { rs ->
                    return if (rs.next()) rs.getInt(1) else 0
                }
            }
        }
    }

    fun resolveRelationshipTargets() {
        synchronized(lock) {
            val conn = getConnection()
            conn.createStatement().use { stmt ->
                stmt.executeUpdate("""
                    UPDATE entity_relationships SET target_entity_id = (
                        SELECT id FROM entities WHERE name = entity_relationships.target_name
                        OR qualified_name = entity_relationships.target_name LIMIT 1
                    ) WHERE target_entity_id IS NULL
                """.trimIndent())
            }
        }
    }

    // ── Line Index CRUD ──────────────────────────────────────────

    fun insertLineIndexBatch(entries: List<LineIndexRecord>) {
        if (entries.isEmpty()) return
        synchronized(lock) {
            val conn = getConnection()
            val wasAutoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                val sql = """
                    INSERT OR REPLACE INTO line_index (file_id, line_num, entity_id, line_type)
                    VALUES (?, ?, ?, ?)
                """.trimIndent()
                conn.prepareStatement(sql).use { ps ->
                    for (e in entries) {
                        ps.setInt(1, e.fileId)
                        ps.setInt(2, e.lineNum)
                        if (e.entityId != null) ps.setInt(3, e.entityId) else ps.setNull(3, java.sql.Types.INTEGER)
                        ps.setString(4, e.lineType)
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

    fun getLineIndex(fileId: Int, lineNum: Int): LineIndexRecord? {
        synchronized(lock) {
            val conn = getConnection()
            conn.prepareStatement("SELECT * FROM line_index WHERE file_id = ? AND line_num = ?").use { ps ->
                ps.setInt(1, fileId)
                ps.setInt(2, lineNum)
                ps.executeQuery().use { rs ->
                    return rs.toLineIndexRecords().firstOrNull()
                }
            }
        }
    }

    fun getLineIndexByFile(fileId: Int): List<LineIndexRecord> {
        synchronized(lock) {
            val conn = getConnection()
            conn.prepareStatement("SELECT * FROM line_index WHERE file_id = ? ORDER BY line_num").use { ps ->
                ps.setInt(1, fileId)
                ps.executeQuery().use { rs ->
                    return rs.toLineIndexRecords()
                }
            }
        }
    }

    fun getLinesByEntity(entityId: Int): List<LineIndexRecord> {
        synchronized(lock) {
            val conn = getConnection()
            conn.prepareStatement("SELECT * FROM line_index WHERE entity_id = ? ORDER BY file_id, line_num").use { ps ->
                ps.setInt(1, entityId)
                ps.executeQuery().use { rs ->
                    return rs.toLineIndexRecords()
                }
            }
        }
    }

    fun deleteLineIndexByFile(fileId: Int) {
        synchronized(lock) {
            val conn = getConnection()
            conn.prepareStatement("DELETE FROM line_index WHERE file_id = ?").use { ps ->
                ps.setInt(1, fileId)
                ps.executeUpdate()
            }
        }
    }

    fun getLineIndexCount(): Int {
        synchronized(lock) {
            val conn = getConnection()
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT COUNT(*) FROM line_index").use { rs ->
                    return if (rs.next()) rs.getInt(1) else 0
                }
            }
        }
    }

    // ── Dependency Graph CRUD ────────────────────────────────────

    fun insertDependencyEdge(
        sourceModuleId: Int?,
        targetModuleId: Int?,
        sourcePath: String,
        targetPath: String,
        depType: String,
        weight: Int = 1
    ): Int {
        synchronized(lock) {
            val conn = getConnection()
            val sql = """
                INSERT INTO dependency_graph (source_module_id, target_module_id, source_path, target_path, dep_type, weight, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
            conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { ps ->
                if (sourceModuleId != null) ps.setInt(1, sourceModuleId) else ps.setNull(1, java.sql.Types.INTEGER)
                if (targetModuleId != null) ps.setInt(2, targetModuleId) else ps.setNull(2, java.sql.Types.INTEGER)
                ps.setString(3, sourcePath)
                ps.setString(4, targetPath)
                ps.setString(5, depType)
                ps.setInt(6, weight)
                ps.setString(7, Instant.now().toString())
                ps.executeUpdate()
                ps.generatedKeys.use { rs ->
                    return if (rs.next()) rs.getInt(1) else -1
                }
            }
        }
    }

    fun getDependenciesByModule(moduleId: Int): List<DependencyEdge> {
        synchronized(lock) {
            val conn = getConnection()
            conn.prepareStatement("SELECT * FROM dependency_graph WHERE source_module_id = ?").use { ps ->
                ps.setInt(1, moduleId)
                ps.executeQuery().use { rs ->
                    return rs.toDependencyEdges()
                }
            }
        }
    }

    fun getDependentsByModule(moduleId: Int): List<DependencyEdge> {
        synchronized(lock) {
            val conn = getConnection()
            conn.prepareStatement("SELECT * FROM dependency_graph WHERE target_module_id = ?").use { ps ->
                ps.setInt(1, moduleId)
                ps.executeQuery().use { rs ->
                    return rs.toDependencyEdges()
                }
            }
        }
    }

    fun clearDependencyGraph() {
        synchronized(lock) {
            val conn = getConnection()
            conn.createStatement().use { stmt ->
                stmt.execute("DELETE FROM dependency_graph")
            }
        }
    }

    fun getDependencyEdgeCount(): Int {
        synchronized(lock) {
            val conn = getConnection()
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT COUNT(*) FROM dependency_graph").use { rs ->
                    return if (rs.next()) rs.getInt(1) else 0
                }
            }
        }
    }

    // ── Efficient COUNT queries (no row loading) ──────────────────

    fun countEntitiesByType(entityType: String): Int {
        synchronized(lock) {
            val conn = getConnection()
            conn.prepareStatement("SELECT COUNT(*) FROM entities WHERE entity_type = ?").use { ps ->
                ps.setString(1, entityType)
                ps.executeQuery().use { rs ->
                    return if (rs.next()) rs.getInt(1) else 0
                }
            }
        }
    }

    fun countRelationshipsByType(relationship: String): Int {
        synchronized(lock) {
            val conn = getConnection()
            conn.prepareStatement("SELECT COUNT(*) FROM entity_relationships WHERE relationship = ?").use { ps ->
                ps.setString(1, relationship)
                ps.executeQuery().use { rs ->
                    return if (rs.next()) rs.getInt(1) else 0
                }
            }
        }
    }

    /**
     * Single JOIN query to aggregate import relationships into directory-level dependency edges.
     * Returns (sourceDir, targetPackage, count) triples.
     * Much faster than N+1 lookups for large repos.
     */
    fun aggregateDependencyEdges(): List<Triple<String, String, Int>> {
        synchronized(lock) {
            val conn = getConnection()
            val sql = """
                SELECT f.relative_path, r.target_name
                FROM entity_relationships r
                JOIN entities e ON r.source_entity_id = e.id
                JOIN files f ON e.file_id = f.id
                WHERE r.relationship = 'imports'
            """
            conn.createStatement().use { stmt ->
                stmt.executeQuery(sql).use { rs ->
                    val edges = mutableMapOf<Pair<String, String>, Int>()
                    while (rs.next()) {
                        val filePath = rs.getString(1)
                        val target = rs.getString(2)
                        val sourceDir = filePath.split("/").take(2).joinToString("/")
                        val targetDir = target.split(".").take(2).joinToString(".")
                        if (sourceDir.isNotBlank() && targetDir.isNotBlank() && sourceDir != targetDir) {
                            val key = Pair(sourceDir, targetDir)
                            edges[key] = (edges[key] ?: 0) + 1
                        }
                    }
                    return edges.map { (k, v) -> Triple(k.first, k.second, v) }
                }
            }
        }
    }

    // ── File Classification CRUD ─────────────────────────────────

    fun insertFileClassification(
        fileId: Int,
        primaryClass: String,
        secondaryClass: String?,
        frameworkHint: String?,
        entryPoint: Boolean = false,
        generated: Boolean = false
    ): Int {
        synchronized(lock) {
            val conn = getConnection()
            val sql = """
                INSERT OR REPLACE INTO file_classifications (file_id, primary_class, secondary_class, framework_hint, entry_point, generated, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
            conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { ps ->
                ps.setInt(1, fileId)
                ps.setString(2, primaryClass)
                ps.setString(3, secondaryClass)
                ps.setString(4, frameworkHint)
                ps.setInt(5, if (entryPoint) 1 else 0)
                ps.setInt(6, if (generated) 1 else 0)
                ps.setString(7, Instant.now().toString())
                ps.executeUpdate()
                ps.generatedKeys.use { rs ->
                    return if (rs.next()) rs.getInt(1) else -1
                }
            }
        }
    }

    fun insertFileClassificationsBatch(classifications: List<FileClassification>) {
        if (classifications.isEmpty()) return
        synchronized(lock) {
            val conn = getConnection()
            val wasAutoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                val sql = """
                    INSERT OR REPLACE INTO file_classifications (file_id, primary_class, secondary_class, framework_hint, entry_point, generated, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
                conn.prepareStatement(sql).use { ps ->
                    for (c in classifications) {
                        ps.setInt(1, c.fileId)
                        ps.setString(2, c.primaryClass)
                        ps.setString(3, c.secondaryClass)
                        ps.setString(4, c.frameworkHint)
                        ps.setInt(5, if (c.entryPoint) 1 else 0)
                        ps.setInt(6, if (c.generated) 1 else 0)
                        ps.setString(7, Instant.now().toString())
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

    fun getFilesByClassification(primaryClass: String): List<FileRecord> {
        synchronized(lock) {
            val conn = getConnection()
            conn.prepareStatement("""
                SELECT f.* FROM files f
                JOIN file_classifications fc ON f.id = fc.file_id
                WHERE fc.primary_class = ?
            """.trimIndent()).use { ps ->
                ps.setString(1, primaryClass)
                ps.executeQuery().use { rs ->
                    return rs.toFileRecords()
                }
            }
        }
    }

    fun getClassificationOverview(): Map<String, Int> {
        synchronized(lock) {
            val conn = getConnection()
            val result = mutableMapOf<String, Int>()
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT primary_class, COUNT(*) as cnt FROM file_classifications GROUP BY primary_class ORDER BY cnt DESC").use { rs ->
                    while (rs.next()) {
                        result[rs.getString("primary_class")] = rs.getInt("cnt")
                    }
                }
            }
            return result
        }
    }

    fun deleteClassificationByFile(fileId: Int) {
        synchronized(lock) {
            val conn = getConnection()
            conn.prepareStatement("DELETE FROM file_classifications WHERE file_id = ?").use { ps ->
                ps.setInt(1, fileId)
                ps.executeUpdate()
            }
        }
    }

    // ── Index Metadata CRUD ──────────────────────────────────────

    fun insertIndexMetadata(indexType: String, status: String, trigger: String): Int {
        synchronized(lock) {
            val conn = getConnection()
            val sql = """
                INSERT INTO index_metadata (index_type, status, started_at, trigger_type)
                VALUES (?, ?, ?, ?)
            """.trimIndent()
            conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { ps ->
                ps.setString(1, indexType)
                ps.setString(2, status)
                ps.setString(3, Instant.now().toString())
                ps.setString(4, trigger)
                ps.executeUpdate()
                ps.generatedKeys.use { rs ->
                    return if (rs.next()) rs.getInt(1) else -1
                }
            }
        }
    }

    fun updateIndexMetadata(
        id: Int,
        status: String,
        completedAt: String? = null,
        durationMs: Long? = null,
        filesProcessed: Int? = null,
        entitiesFound: Int? = null,
        relationshipsFound: Int? = null,
        errors: String? = null
    ) {
        synchronized(lock) {
            val conn = getConnection()
            val sql = """
                UPDATE index_metadata SET status = ?, completed_at = ?, duration_ms = ?,
                files_processed = ?, entities_found = ?, relationships_found = ?, errors = ?
                WHERE id = ?
            """.trimIndent()
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, status)
                ps.setString(2, completedAt)
                if (durationMs != null) ps.setLong(3, durationMs) else ps.setNull(3, java.sql.Types.INTEGER)
                if (filesProcessed != null) ps.setInt(4, filesProcessed) else ps.setNull(4, java.sql.Types.INTEGER)
                if (entitiesFound != null) ps.setInt(5, entitiesFound) else ps.setNull(5, java.sql.Types.INTEGER)
                if (relationshipsFound != null) ps.setInt(6, relationshipsFound) else ps.setNull(6, java.sql.Types.INTEGER)
                ps.setString(7, errors)
                ps.setInt(8, id)
                ps.executeUpdate()
            }
        }
    }

    fun getLatestIndexMetadata(indexType: String): IndexMetadataRecord? {
        synchronized(lock) {
            val conn = getConnection()
            conn.prepareStatement("SELECT * FROM index_metadata WHERE index_type = ? ORDER BY id DESC LIMIT 1").use { ps ->
                ps.setString(1, indexType)
                ps.executeQuery().use { rs ->
                    return rs.toIndexMetadataRecords().firstOrNull()
                }
            }
        }
    }

    fun getIndexStats(): Map<String, Any> {
        synchronized(lock) {
            val conn = getConnection()
            val stats = mutableMapOf<String, Any>()
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT COUNT(*) FROM entities").use { rs ->
                    if (rs.next()) stats["entity_count"] = rs.getInt(1)
                }
            }
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT COUNT(*) FROM entity_relationships").use { rs ->
                    if (rs.next()) stats["relationship_count"] = rs.getInt(1)
                }
            }
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT COUNT(*) FROM line_index").use { rs ->
                    if (rs.next()) stats["line_index_count"] = rs.getInt(1)
                }
            }
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT COUNT(*) FROM dependency_graph").use { rs ->
                    if (rs.next()) stats["dependency_edge_count"] = rs.getInt(1)
                }
            }
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT COUNT(*) FROM file_classifications").use { rs ->
                    if (rs.next()) stats["classification_count"] = rs.getInt(1)
                }
            }
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT COUNT(DISTINCT entity_type) FROM entities").use { rs ->
                    if (rs.next()) stats["distinct_entity_types"] = rs.getInt(1)
                }
            }
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT COUNT(DISTINCT relationship) FROM entity_relationships").use { rs ->
                    if (rs.next()) stats["distinct_relationship_types"] = rs.getInt(1)
                }
            }
            return stats
        }
    }

    // ── Project Index ResultSet mapping helpers ───────────────────

    private fun ResultSet.toEntityRecords(): List<EntityRecord> {
        val records = mutableListOf<EntityRecord>()
        while (next()) {
            records.add(
                EntityRecord(
                    id = getInt("id"),
                    fileId = getInt("file_id"),
                    moduleId = getInt("module_id").let { if (wasNull()) null else it },
                    entityType = getString("entity_type"),
                    name = getString("name"),
                    qualifiedName = getString("qualified_name"),
                    parentEntityId = getInt("parent_entity_id").let { if (wasNull()) null else it },
                    language = getString("language"),
                    visibility = getString("visibility"),
                    signature = getString("signature"),
                    startLine = getInt("start_line"),
                    endLine = getInt("end_line"),
                    sha256 = getString("sha256")
                )
            )
        }
        return records
    }

    private fun ResultSet.toEntityRecordsWithPath(): List<EntityRecord> {
        val records = mutableListOf<EntityRecord>()
        while (next()) {
            records.add(
                EntityRecord(
                    id = getInt("id"),
                    fileId = getInt("file_id"),
                    moduleId = getInt("module_id").let { if (wasNull()) null else it },
                    entityType = getString("entity_type"),
                    name = getString("name"),
                    qualifiedName = getString("qualified_name"),
                    parentEntityId = getInt("parent_entity_id").let { if (wasNull()) null else it },
                    language = getString("language"),
                    visibility = getString("visibility"),
                    signature = getString("signature"),
                    startLine = getInt("start_line"),
                    endLine = getInt("end_line"),
                    sha256 = getString("sha256"),
                    filePath = try { getString("file_path") } catch (_: Exception) { null }
                )
            )
        }
        return records
    }

    private fun ResultSet.toRelationshipRecords(): List<EntityRelationshipRecord> {
        val records = mutableListOf<EntityRelationshipRecord>()
        while (next()) {
            records.add(
                EntityRelationshipRecord(
                    id = getInt("id"),
                    sourceEntityId = getInt("source_entity_id"),
                    targetEntityId = getInt("target_entity_id").let { if (wasNull()) null else it },
                    targetName = getString("target_name"),
                    relationship = getString("relationship"),
                    confidence = getDouble("confidence"),
                    sourceLine = getInt("source_line").let { if (wasNull()) null else it }
                )
            )
        }
        return records
    }

    private fun ResultSet.toLineIndexRecords(): List<LineIndexRecord> {
        val records = mutableListOf<LineIndexRecord>()
        while (next()) {
            records.add(
                LineIndexRecord(
                    id = getInt("id"),
                    fileId = getInt("file_id"),
                    lineNum = getInt("line_num"),
                    entityId = getInt("entity_id").let { if (wasNull()) null else it },
                    lineType = getString("line_type")
                )
            )
        }
        return records
    }

    private fun ResultSet.toDependencyEdges(): List<DependencyEdge> {
        val records = mutableListOf<DependencyEdge>()
        while (next()) {
            records.add(
                DependencyEdge(
                    id = getInt("id"),
                    sourceModuleId = getInt("source_module_id").let { if (wasNull()) null else it },
                    targetModuleId = getInt("target_module_id").let { if (wasNull()) null else it },
                    sourcePath = getString("source_path"),
                    targetPath = getString("target_path"),
                    depType = getString("dep_type"),
                    weight = getInt("weight")
                )
            )
        }
        return records
    }

    private fun ResultSet.toFileClassifications(): List<FileClassification> {
        val records = mutableListOf<FileClassification>()
        while (next()) {
            records.add(
                FileClassification(
                    id = getInt("id"),
                    fileId = getInt("file_id"),
                    primaryClass = getString("primary_class"),
                    secondaryClass = getString("secondary_class"),
                    frameworkHint = getString("framework_hint"),
                    entryPoint = getInt("entry_point") == 1,
                    generated = getInt("generated") == 1
                )
            )
        }
        return records
    }

    private fun ResultSet.toIndexMetadataRecords(): List<IndexMetadataRecord> {
        val records = mutableListOf<IndexMetadataRecord>()
        while (next()) {
            records.add(
                IndexMetadataRecord(
                    id = getInt("id"),
                    indexType = getString("index_type"),
                    status = getString("status"),
                    startedAt = getString("started_at"),
                    completedAt = getString("completed_at"),
                    durationMs = getLong("duration_ms").let { if (wasNull()) null else it },
                    filesProcessed = getInt("files_processed"),
                    entitiesFound = getInt("entities_found"),
                    relationshipsFound = getInt("relationships_found"),
                    errors = getString("errors"),
                    commitSha = getString("commit_sha"),
                    trigger = getString("trigger_type")
                )
            )
        }
        return records
    }
}
