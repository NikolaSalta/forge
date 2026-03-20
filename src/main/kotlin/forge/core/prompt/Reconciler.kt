package forge.core.prompt

import forge.ForgeConfig

/**
 * Severity of a detected contradiction between partition outputs.
 */
enum class ContradictionSeverity {
    /** Minor discrepancy, likely different perspectives on the same topic. */
    LOW,
    /** Moderate conflict that should be noted in the synthesis. */
    MEDIUM,
    /** Direct contradiction that may invalidate one or both findings. */
    HIGH
}

/**
 * A contradiction detected between two partition outputs.
 */
data class Contradiction(
    /** ID of the first conflicting partition. */
    val partitionA: String,
    /** ID of the second conflicting partition. */
    val partitionB: String,
    /** Human-readable description of the contradiction. */
    val description: String,
    /** How severe the contradiction is. */
    val severity: ContradictionSeverity
)

/**
 * The result of reconciling outputs from multiple partitions.
 */
data class ReconciliationReport(
    /** Contradictions detected between partition outputs. */
    val contradictions: List<Contradiction>,
    /** Artifacts that were declared by partitions but not produced. */
    val missingArtifacts: List<String>,
    /** Dependencies that completed but produced no usable response. */
    val unresolvedDeps: List<String>,
    /** Whether additional retrieval may be needed to resolve gaps. */
    val needsAdditionalRetrieval: Boolean
) {
    /** True if the report found any issues. */
    val hasIssues: Boolean get() =
        contradictions.isNotEmpty() || missingArtifacts.isNotEmpty() || unresolvedDeps.isNotEmpty()

    /** Count of all issues found. */
    val issueCount: Int get() =
        contradictions.size + missingArtifacts.size + unresolvedDeps.size
}

/**
 * Cross-task reconciliation engine. Sits between [ParallelExecutor] and
 * [ResultSynthesizer] to detect inconsistencies across partition outputs.
 *
 * Three checks are performed:
 * 1. **Contradiction detection** — heuristic extraction of key claims (technology names,
 *    file references, architecture patterns) and cross-comparison.
 * 2. **Missing artifact detection** — comparing declared `producesArtifacts` against
 *    actual `PartitionResult.artifacts`.
 * 3. **Unresolved dependency detection** — verifying every `dependsOn` reference has
 *    a COMPLETED result with non-empty response.
 */
class Reconciler(
    @Suppress("unused") private val config: ForgeConfig
) {
    // Technology/framework keywords to extract as claims from partition responses
    private val technologyPatterns = listOf(
        "postgresql", "mysql", "mongodb", "redis", "sqlite", "cassandra", "dynamodb",
        "spring boot", "spring", "fastapi", "express", "django", "flask", "nestjs",
        "react", "vue", "angular", "svelte", "next.js", "nuxt",
        "kotlin", "java", "python", "typescript", "javascript", "go", "rust",
        "docker", "kubernetes", "terraform", "ansible",
        "rabbitmq", "kafka", "redis pub/sub", "nats",
        "graphql", "rest", "grpc", "websocket",
        "jwt", "oauth", "saml", "basic auth",
        "gradle", "maven", "npm", "pnpm", "yarn", "pip", "poetry",
        "junit", "pytest", "jest", "mocha", "vitest"
    )

    // Architecture pattern keywords
    private val architecturePatterns = listOf(
        "monolith", "microservices", "serverless", "event-driven", "cqrs",
        "layered architecture", "hexagonal", "clean architecture", "mvc", "mvvm",
        "synchronous", "asynchronous", "pub/sub", "request/reply",
        "sql", "nosql", "relational", "document store"
    )

    // Negation indicators that flip a claim's meaning
    private val negationIndicators = listOf(
        "not using", "doesn't use", "does not use", "no ", "without ",
        "lacks ", "missing ", "absent", "deprecated", "removed", "replaced"
    )

    /**
     * Reconcile the outputs from all partitions, looking for contradictions,
     * missing artifacts, and unresolved dependencies.
     */
    fun reconcile(
        plan: ExecutionPlan,
        results: Map<String, PartitionResult>
    ): ReconciliationReport {
        val contradictions = detectContradictions(plan, results)
        val missingArtifacts = detectMissingArtifacts(plan, results)
        val unresolvedDeps = detectUnresolvedDeps(plan, results)

        val needsRetrieval = contradictions.any { it.severity == ContradictionSeverity.HIGH } ||
                missingArtifacts.isNotEmpty()

        return ReconciliationReport(
            contradictions = contradictions,
            missingArtifacts = missingArtifacts,
            unresolvedDeps = unresolvedDeps,
            needsAdditionalRetrieval = needsRetrieval
        )
    }

    // ── Contradiction detection ──────────────────────────────────────────────

    /**
     * Extract technology/architecture claims from each partition response and
     * cross-compare for conflicts. Uses heuristic keyword extraction — no LLM call.
     */
    private fun detectContradictions(
        plan: ExecutionPlan,
        results: Map<String, PartitionResult>
    ): List<Contradiction> {
        val contradictions = mutableListOf<Contradiction>()

        // Extract claims from each completed partition
        val claimsByPartition = mutableMapOf<String, PartitionClaims>()
        for (partition in plan.partitions) {
            val result = results[partition.id] ?: continue
            if (result.status != PartitionStatus.COMPLETED || result.response.isNullOrBlank()) continue
            claimsByPartition[partition.id] = extractClaims(result.response)
        }

        // Cross-compare claims between all partition pairs
        val partitionIds = claimsByPartition.keys.toList()
        for (i in partitionIds.indices) {
            for (j in i + 1 until partitionIds.size) {
                val idA = partitionIds[i]
                val idB = partitionIds[j]
                val claimsA = claimsByPartition[idA] ?: continue
                val claimsB = claimsByPartition[idB] ?: continue

                contradictions.addAll(compareClaimSets(idA, idB, claimsA, claimsB))
            }
        }

        return contradictions
    }

    /**
     * Claims extracted from a single partition's response.
     */
    private data class PartitionClaims(
        /** Technologies positively mentioned. */
        val presentTechnologies: Set<String>,
        /** Technologies mentioned as absent/not used. */
        val absentTechnologies: Set<String>,
        /** Architecture patterns positively mentioned. */
        val presentArchitecture: Set<String>,
        /** Architecture patterns mentioned as absent/not applicable. */
        val absentArchitecture: Set<String>
    )

    /**
     * Extract claims about technologies and architecture patterns from response text.
     */
    private fun extractClaims(response: String): PartitionClaims {
        val lower = response.lowercase()
        val lines = lower.lines()

        val presentTech = mutableSetOf<String>()
        val absentTech = mutableSetOf<String>()
        val presentArch = mutableSetOf<String>()
        val absentArch = mutableSetOf<String>()

        for (line in lines) {
            val isNegated = negationIndicators.any { line.contains(it) }

            for (tech in technologyPatterns) {
                if (line.contains(tech)) {
                    if (isNegated) absentTech.add(tech) else presentTech.add(tech)
                }
            }

            for (arch in architecturePatterns) {
                if (line.contains(arch)) {
                    if (isNegated) absentArch.add(arch) else presentArch.add(arch)
                }
            }
        }

        return PartitionClaims(presentTech, absentTech, presentArch, absentArch)
    }

    /**
     * Compare claims from two partitions and flag contradictions.
     * A contradiction occurs when one partition asserts presence and another
     * asserts absence of the same technology or pattern.
     */
    private fun compareClaimSets(
        idA: String,
        idB: String,
        claimsA: PartitionClaims,
        claimsB: PartitionClaims
    ): List<Contradiction> {
        val result = mutableListOf<Contradiction>()

        // Technology contradictions: A says present, B says absent (or vice versa)
        val techConflictsAB = claimsA.presentTechnologies.intersect(claimsB.absentTechnologies)
        val techConflictsBA = claimsA.absentTechnologies.intersect(claimsB.presentTechnologies)

        for (tech in techConflictsAB) {
            result.add(Contradiction(
                partitionA = idA,
                partitionB = idB,
                description = "Partition $idA states '$tech' is used, but partition $idB states it is not used",
                severity = ContradictionSeverity.MEDIUM
            ))
        }
        for (tech in techConflictsBA) {
            result.add(Contradiction(
                partitionA = idA,
                partitionB = idB,
                description = "Partition $idA states '$tech' is not used, but partition $idB states it is used",
                severity = ContradictionSeverity.MEDIUM
            ))
        }

        // Architecture contradictions
        val archConflictsAB = claimsA.presentArchitecture.intersect(claimsB.absentArchitecture)
        val archConflictsBA = claimsA.absentArchitecture.intersect(claimsB.presentArchitecture)

        for (arch in archConflictsAB + archConflictsBA) {
            result.add(Contradiction(
                partitionA = idA,
                partitionB = idB,
                description = "Conflicting architecture claims about '$arch' between partitions $idA and $idB",
                severity = ContradictionSeverity.HIGH
            ))
        }

        return result
    }

    // ── Missing artifact detection ───────────────────────────────────────────

    /**
     * Check that every artifact declared in a partition's `producesArtifacts` list
     * was actually produced in its `PartitionResult.artifacts`.
     */
    private fun detectMissingArtifacts(
        plan: ExecutionPlan,
        results: Map<String, PartitionResult>
    ): List<String> {
        val missing = mutableListOf<String>()
        for (partition in plan.partitions) {
            if (partition.producesArtifacts.isEmpty()) continue
            val result = results[partition.id] ?: continue
            if (result.status != PartitionStatus.COMPLETED) continue

            for (artifact in partition.producesArtifacts) {
                if (!result.artifacts.containsKey(artifact)) {
                    missing.add("[${partition.id}] declared artifact '$artifact' was not produced")
                }
            }
        }
        return missing
    }

    // ── Unresolved dependency detection ──────────────────────────────────────

    /**
     * Verify that every partition's dependencies completed successfully with
     * a non-empty response. Flag cases where a dependency completed but
     * produced an empty or null response.
     */
    private fun detectUnresolvedDeps(
        plan: ExecutionPlan,
        results: Map<String, PartitionResult>
    ): List<String> {
        val unresolved = mutableListOf<String>()
        for (partition in plan.partitions) {
            for (depId in partition.dependsOn) {
                val depResult = results[depId]
                when {
                    depResult == null ->
                        unresolved.add("[${partition.id}] dependency '$depId' has no result")
                    depResult.status == PartitionStatus.COMPLETED && depResult.response.isNullOrBlank() ->
                        unresolved.add("[${partition.id}] dependency '$depId' completed but produced empty response")
                    depResult.status == PartitionStatus.FAILED ->
                        unresolved.add("[${partition.id}] dependency '$depId' failed: ${depResult.error}")
                    depResult.status == PartitionStatus.SKIPPED ->
                        unresolved.add("[${partition.id}] dependency '$depId' was skipped: ${depResult.error}")
                }
            }
        }
        return unresolved
    }
}
