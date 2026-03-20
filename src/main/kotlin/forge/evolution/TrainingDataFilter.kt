package forge.evolution

/**
 * Filters training data to remove sensitive content before dataset export.
 * Applies PII detection, credential stripping, and content sanitization.
 */
class TrainingDataFilter(
    private val piiFilterEnabled: Boolean = true
) {
    // Patterns that indicate sensitive content
    private val sensitivePatterns = listOf(
        // API keys and tokens
        Regex("""(?i)(api[_-]?key|token|secret|password|credential)\s*[:=]\s*\S+"""),
        // Bearer tokens
        Regex("""(?i)bearer\s+[a-zA-Z0-9._\-]+"""),
        // Private keys
        Regex("""-----BEGIN (RSA |EC |DSA )?PRIVATE KEY-----"""),
        // AWS keys
        Regex("""(?i)(AKIA|ASIA)[A-Z0-9]{16}"""),
        // Email addresses
        Regex("""[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}"""),
        // IP addresses with ports (may indicate internal infrastructure)
        Regex("""\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}:\d+\b"""),
    )

    // Patterns to redact file paths with usernames
    private val pathPatterns = listOf(
        Regex("""/Users/[a-zA-Z0-9._-]+/"""),
        Regex("""C:\\Users\\[a-zA-Z0-9._-]+\\"""),
        Regex("""/home/[a-zA-Z0-9._-]+/"""),
    )

    /**
     * Returns true if the content is safe for training data export.
     */
    fun isSafe(content: String): Boolean {
        if (!piiFilterEnabled) return true

        for (pattern in sensitivePatterns) {
            if (pattern.containsMatchIn(content)) return false
        }
        return true
    }

    /**
     * Sanitize content by redacting sensitive information.
     * Returns the sanitized version.
     */
    fun sanitize(content: String): String {
        if (!piiFilterEnabled) return content

        var result = content

        // Redact sensitive patterns
        for (pattern in sensitivePatterns) {
            result = pattern.replace(result, "[REDACTED]")
        }

        // Anonymize file paths
        for (pattern in pathPatterns) {
            result = pattern.replace(result, "/Users/[user]/")
        }

        return result
    }

    /**
     * Check if a specific Q/A pair passes all safety checks.
     */
    fun validatePair(input: String, output: String): FilterResult {
        val issues = mutableListOf<String>()

        if (!isSafe(input)) issues.add("Input contains sensitive data")
        if (!isSafe(output)) issues.add("Output contains sensitive data")

        // Check for raw stack traces (usually noise, not good training data)
        if (output.count { it == '\n' } > 100 &&
            output.contains("at ") && output.contains(".java:")) {
            issues.add("Output appears to contain raw stack traces")
        }

        return FilterResult(
            passed = issues.isEmpty(),
            issues = issues
        )
    }
}

data class FilterResult(
    val passed: Boolean,
    val issues: List<String> = emptyList()
)
