package forge.web

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Security utilities for the FORGE web server.
 *
 * Provides:
 * - Path validation (prevents traversal attacks, blocks sensitive directories)
 * - Input sanitization (query length limits, module name validation)
 * - Rate limiting (in-memory token bucket per endpoint category)
 * - Optional bearer token authentication
 */
object Security {

    // ── Sensitive directories that should never be browsable ──────────────

    private val BLOCKED_DIRS = setOf(
        ".ssh", ".gnupg", ".aws", ".azure", ".config/gcloud",
        ".kube", ".docker", ".npmrc", ".pypirc",
        "Library/Keychains", ".password-store",
        ".git/objects" // internal git data
    )

    private val BLOCKED_FILE_PATTERNS = listOf(
        Regex("""\.env(\.\w+)?$"""),
        Regex("""credentials\.json$"""),
        Regex("""\.pem$"""),
        Regex("""\.key$"""),
        Regex("""id_rsa"""),
        Regex("""id_ed25519"""),
        Regex("""\.secret""")
    )

    // ── Path validation ─────────────────────────────────────────────────

    /**
     * Validates a browseable path.
     * - Must exist and be a directory
     * - Must not escape via symlinks to sensitive locations
     * - Must not be in the blocked directory list
     *
     * @return null if valid, error message string if invalid
     */
    fun validateBrowsePath(path: Path): String? {
        val resolved = path.toAbsolutePath().normalize()

        if (!Files.exists(resolved)) {
            return "Path does not exist"
        }

        if (!Files.isDirectory(resolved)) {
            return "Path is not a directory"
        }

        // Check if path resolves through symlinks to a different location
        try {
            val real = resolved.toRealPath()
            // Allow symlinks but check the real path isn't in a blocked location
            if (isBlockedPath(real)) {
                return "Access to this directory is restricted"
            }
        } catch (_: Exception) {
            return "Cannot resolve path"
        }

        if (isBlockedPath(resolved)) {
            return "Access to this directory is restricted"
        }

        return null // valid
    }

    private fun isBlockedPath(path: Path): Boolean {
        val pathStr = path.toString()
        return BLOCKED_DIRS.any { blocked ->
            pathStr.contains("/$blocked") || pathStr.contains("\\$blocked")
        }
    }

    // ── Input validation ─────────────────────────────────────────────────

    /** Maximum query length in characters. */
    const val MAX_QUERY_LENGTH = 50_000

    /** Maximum module name length. */
    const val MAX_MODULE_LENGTH = 500

    /** Pattern for valid module names. */
    private val MODULE_NAME_REGEX = Regex("""^[\w./:@-]+$""")

    /**
     * Validate a user query string.
     * @return null if valid, error message if invalid
     */
    fun validateQuery(query: String): String? {
        if (query.isBlank()) return "Query cannot be empty"
        if (query.length > MAX_QUERY_LENGTH) return "Query exceeds maximum length of $MAX_QUERY_LENGTH characters"
        return null
    }

    /**
     * Validate a module name.
     * @return null if valid, error message if invalid
     */
    fun validateModuleName(module: String): String? {
        if (module.isBlank()) return "Module name cannot be empty"
        if (module.length > MAX_MODULE_LENGTH) return "Module name too long"
        if (!MODULE_NAME_REGEX.matches(module)) return "Module name contains invalid characters"
        return null
    }

    // ── Rate limiting ─────────────────────────────────────────────────────

    /**
     * Simple in-memory token bucket rate limiter.
     * Each bucket is identified by a string key (e.g., endpoint name).
     */
    class RateLimiter(
        private val maxTokens: Int = 10,
        private val refillPerSecond: Double = 2.0
    ) {
        private data class Bucket(
            val tokens: AtomicLong = AtomicLong(10),
            val lastRefill: AtomicLong = AtomicLong(System.currentTimeMillis())
        )

        private val buckets = ConcurrentHashMap<String, Bucket>()

        /**
         * Try to consume a token from the named bucket.
         * @return true if allowed, false if rate limited
         */
        fun tryAcquire(key: String): Boolean {
            val bucket = buckets.computeIfAbsent(key) {
                Bucket(AtomicLong(maxTokens.toLong()), AtomicLong(System.currentTimeMillis()))
            }

            // Refill based on elapsed time
            val now = System.currentTimeMillis()
            val elapsed = now - bucket.lastRefill.get()
            if (elapsed > 1000) {
                val refillTokens = (elapsed / 1000.0 * refillPerSecond).toLong()
                if (refillTokens > 0) {
                    bucket.lastRefill.set(now)
                    val current = bucket.tokens.get()
                    bucket.tokens.set(minOf(current + refillTokens, maxTokens.toLong()))
                }
            }

            // Try to consume
            while (true) {
                val current = bucket.tokens.get()
                if (current <= 0) return false
                if (bucket.tokens.compareAndSet(current, current - 1)) return true
            }
        }

        /**
         * Clean up old buckets that haven't been used in a while.
         */
        fun cleanup(maxAgeMs: Long = 300_000) {
            val cutoff = System.currentTimeMillis() - maxAgeMs
            buckets.entries.removeIf { it.value.lastRefill.get() < cutoff }
        }
    }

    // Global rate limiters for different endpoint categories
    val analyzeLimiter = RateLimiter(maxTokens = 3, refillPerSecond = 0.1) // ~6 per minute
    val askLimiter = RateLimiter(maxTokens = 10, refillPerSecond = 1.0)    // ~60 per minute
    val browseLimiter = RateLimiter(maxTokens = 30, refillPerSecond = 5.0) // generous for browsing

    // ── Bearer token auth ─────────────────────────────────────────────────

    @Volatile
    private var authToken: String? = null

    /**
     * Generate and set a random auth token.
     * @return the generated token
     */
    fun generateAuthToken(): String {
        val token = java.util.UUID.randomUUID().toString()
        authToken = token
        return token
    }

    /**
     * Check if auth is enabled and validate the token.
     * @return true if auth is disabled or token is valid
     */
    fun validateAuth(authHeader: String?): Boolean {
        val token = authToken ?: return true // auth not enabled
        if (authHeader == null) return false
        val bearer = authHeader.removePrefix("Bearer ").trim()
        return bearer == token
    }

    /**
     * Whether auth token is currently configured.
     */
    val isAuthEnabled: Boolean get() = authToken != null
}
