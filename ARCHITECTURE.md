# FORGE v2 — Architecture

## System Overview

FORGE is a pipeline-based code intelligence platform with an embedded web server. User queries flow through a multi-stage pipeline (intent → scan → chunk → embed → evidence → context → LLM), with results streamed back via Server-Sent Events (SSE).

```
┌─────────────────────────────────────────────────────────────┐
│                    Browser (localhost:3456)                   │
│  ┌──────┐  ┌───────────┐  ┌────────┐  ┌───────┐            │
│  │ Chat │  │ Evolution │  │ Config │  │ About │            │
│  └──┬───┘  └───────────┘  └────────┘  └───────┘            │
│     │  POST /api/ask/stream                                  │
│     │  ← SSE: stage_started, llm_token, analysis_progress   │
└─────┼───────────────────────────────────────────────────────┘
      │
      ▼
┌─────────────────────────────────────────────────────────────┐
│              ForgeServer.kt (Ktor + Netty)                   │
│  127.0.0.1:3456 — localhost only                             │
│  CORS · HMAC · Rate Limiting · Input Sanitization            │
├─────────────────────────────────────────────────────────────┤
│              ApiRoutes.kt → Orchestrator                     │
│  Channel<TraceEvent> bridges pipeline ↔ SSE response         │
└─────┬───────────────────────────────────────────────────────┘
      │
      ▼
┌─────────────────────────────────────────────────────────────┐
│                    Orchestrator.kt                            │
│                                                              │
│  executeWithTrace(userInput, repoPath, traceChannel)         │
│                                                              │
│  1. INTENT         → IntentResolver → TaskType               │
│  2. WORKSPACE      → WorkspaceManager → Database             │
│  3. Pipeline stages (SCAN → CHUNK → EMBED → EVIDENCE → ...)  │
│  4. LLM_CALL       → Standard streaming OR DeepAnalyzer      │
│  5. VALIDATE       → ResponseParser.toMarkdown()             │
│  6. TrainingDataCollector.collect()                           │
└─────────────────────────────────────────────────────────────┘
```

## Pipeline Stages

Built by `buildPipeline(taskType)` in `Pipeline.kt`. Each stage reads/writes to a shared `PipelineContext`.

| Stage | Component | What it does |
|-------|-----------|-------------|
| SCAN | RepoScanner | Walk file tree, insert file records into SQLite |
| MODULE_DISCOVERY | IntelliJModuleResolver | Discover IntelliJ modules (when enabled) |
| CHUNK | Chunker | Split source files into semantic chunks (80 lines, 10 overlap) |
| EMBED | EmbeddingStore | Generate embeddings via `nomic-embed-text` (768-dim) |
| EVIDENCE | EvidenceCollector | Run 15+ detectors (build system, languages, monorepo, etc.) |
| CONTEXT_ASSEMBLY | EmbeddingStore + ContextAssembler | Find similar chunks via cosine similarity |
| LLM_CALL | OllamaClient.chatStream() or DeepAnalyzer | Generate response with token-level streaming |
| VALIDATE | ResponseParser | Strip thinking tags, normalize markdown |

## SSE Streaming Architecture

The `executeWithTrace()` method in Orchestrator uses a Kotlin `Channel<TraceEvent>` to bridge pipeline execution with SSE output:

```
Orchestrator (coroutine)          ApiRoutes (SSE writer)
    │                                 │
    ├── send(stageStarted)  ─────►   write("data: {...}\n\n")
    ├── send(stageCompleted) ────►   write("data: {...}\n\n")
    ├── send(modelSelected)  ────►   write("data: {...}\n\n")
    ├── send(llmToken)       ────►   write("data: {...}\n\n")  × N
    ├── send(analysisProgress) ──►   write("data: {...}\n\n")
    └── send(done)           ────►   write("data: {...}\n\n")
```

TraceEvent types:
- `stage_started` / `stage_completed` — Pipeline stage lifecycle
- `intent_resolved` — Task classification result
- `model_selected` — Which Ollama model was chosen
- `llm_token` — Individual token from streaming LLM response
- `analysis_progress` — Deep analysis progress (module X of Y, percent)
- `error` — Stage failure
- `done` — Pipeline complete with final response

## Deep Multi-Pass Analysis (DeepAnalyzer)

For REPO_ANALYSIS, PROJECT_OVERVIEW, and ARCHITECTURE_REVIEW tasks, the standard single LLM call is replaced by DeepAnalyzer:

```
┌──────────────────────────────────────────────────────────┐
│                    DeepAnalyzer                            │
│                                                          │
│  1. discoverModules(evidence, files)                     │
│     ├── Parse MONOREPO_STRUCTURE evidence keys           │
│     │   "sub_service:microservices/alerts" → path        │
│     └── Fallback: 2-level directory scan                 │
│                                                          │
│  2. FOR EACH module (with progress events):              │
│     ├── Read all source files (48K char budget)          │
│     │   Priority: build files > entry points > models    │
│     │   > controllers > services > tests                 │
│     ├── Build deep analysis prompt                       │
│     ├── Stream LLM response (per-token SSE)              │
│     ├── Cache to ~/.forge/workspaces/{hash}/analysis/    │
│     └── Emit analysisProgress event                      │
│                                                          │
│  3. Synthesize all module analyses                        │
│     ├── Combine module analyses (64K char budget)        │
│     ├── Add repository-level evidence                    │
│     ├── Stream synthesis LLM response                    │
│     └── Cache to analysis/_SYNTHESIS.md                  │
└──────────────────────────────────────────────────────────┘
```

Result: A 17-service monorepo gets 18 LLM calls (17 modules + 1 synthesis), producing exhaustive class-level, method-level, and dependency-level detail.

## Evidence Collection

`EvidenceCollector.kt` runs detectors based on TaskType requirements. Evidence is stored in SQLite with schema v2 path-qualified keys:

```
category: MONOREPO_STRUCTURE
key:      sub_service:microservices/alerts     ← unique per service
value:    microservices/alerts: docker=yes, build=npm, lang=TypeScript(38), files=39
```

Previous schema v1 used generic keys (`"sub_service"` for all services), causing map collisions. Schema v2 migration clears stale evidence on first run.

Evidence categories:
- `BUILD_SYSTEM` — Gradle, Maven, npm, pip, Cargo detection
- `LANGUAGES` — Top languages by file count
- `MONOREPO_STRUCTURE` — Sub-service discovery (3 levels deep)
- `SOURCE_ROOTS` — Main source directories
- `TEST_ROOTS` — Test directories and frameworks
- `CI_CD_SIGNALS` — CI/CD pipeline detection
- `DEPENDENCIES` — Key dependencies
- `CONFIG_FILES` — Configuration file inventory
- `KEY_MODULES` — Most important modules by size/connectivity
- `INTEGRATION_POINTS` — API endpoints, message queues

Evidence prioritization in `PromptBuilder.prioritizeEvidence()`:
- **High priority** (always included): MONOREPO_STRUCTURE, BUILD_SYSTEM, LANGUAGES, CI_CD, DEPENDENCIES
- **Medium priority** (capped at 20): SOURCE_ROOTS, TEST_ROOTS, CONFIG_FILES
- **Low priority** (capped at 10-30): KEY_MODULES, INTEGRATION_POINTS

## Retrieval Architecture

### Non-IntelliJ repos (e.g., typical monorepos)

Global embedding + cosine similarity:

```
EMBED stage: embedAllChunksAsync() → all chunks get embeddings
CONTEXT_ASSEMBLY: findSimilarAsync(query, topK=40, threshold=0.50)
  1. Generate query embedding via Ollama
  2. Load all chunks WHERE embedding IS NOT NULL AND length(embedding) > 0
  3. Compute cosine similarity for each
  4. Return top-40 above threshold
```

### IntelliJ-scale repos (180K+ files)

Two-stage hierarchical retrieval to avoid OOM:

```
Stage 1: findRelevantModules(query, topK=5)
  ├── Keyword match on module name/path/dependencies
  ├── Summary embedding similarity (if module has AI summary)
  └── Return top-5 modules

Stage 2: findRelevantChunksInModules(query, modules)
  ├── Lazy embedding: generate embeddings on-demand per module
  ├── Load up to 5000 chunks with embeddings from selected modules
  ├── Cosine similarity ranking
  └── Return top-40 chunks
```

## Prompt Decomposition

Complex multi-part prompts (e.g., "analyze security AND generate tests AND review API design") are decomposed:

```
┌─────────────┐    ┌──────────────────┐    ┌──────────────────┐
│ PromptAnalyzer│ → │ ExecutionPlanner  │ → │ ParallelExecutor  │
│ Complexity:   │    │ DAG of partitions │    │ Semaphore-limited │
│ SIMPLE/       │    │ with dependencies │    │ concurrent LLM    │
│ COMPOUND/     │    │                  │    │ calls             │
│ MULTI_STAGE   │    │                  │    │                   │
└─────────────┘    └──────────────────┘    └────────┬──────────┘
                                                     │
                                           ┌─────────▼──────────┐
                                           │    Reconciler       │
                                           │ Detect contradictions│
                                           └─────────┬──────────┘
                                                     │
                                           ┌─────────▼──────────┐
                                           │ ResultSynthesizer   │
                                           │ Merge into unified  │
                                           │ response            │
                                           └────────────────────┘
```

## Model Evolution Subsystem

Collects training data from every interaction for future fine-tuning:

```
User query + LLM response
  │
  ├── QualityScorer.score() → quality metrics
  ├── TrainingDataFilter.filter() → PII removal
  └── TrainingDataCollector.collect() → stored in workspace DB

Later:
  ├── DatasetBuilder.export() → fine-tuning dataset
  └── ModelEvolutionPlanner.plan() → when to fine-tune
```

## Database Schema

SQLite with WAL mode, 8 tables:

```sql
project_meta    -- Key-value store (schema version, scan status)
repos           -- Repositories (primary + satellites)
modules         -- IntelliJ modules (name, path, type, dependencies, summary)
files           -- Source files (path, language, size, sha256, module_id)
chunks          -- Code chunks (content, start/end line, symbol, embedding BLOB)
chunks_fts      -- FTS5 virtual table for full-text search
evidence        -- Detected facts (category, key, value) with UNIQUE constraint
tasks           -- Query history (type, status, model, result)
```

Key optimizations:
- `PRAGMA journal_mode=WAL` — concurrent reads during writes
- `PRAGMA busy_timeout=30000` — protect against SQLITE_BUSY
- Batch INSERT (500 per transaction)
- `INSERT OR IGNORE` for evidence deduplication
- Evidence schema versioning for cache invalidation

## Security Model

```
┌─────────────────────────────────────┐
│        ForgeServer                   │
│  ┌─────────────────────────────┐    │
│  │ 127.0.0.1 binding only      │    │
│  │ (not accessible from LAN)   │    │
│  ├─────────────────────────────┤    │
│  │ CORS: localhost origins only │    │
│  ├─────────────────────────────┤    │
│  │ HMAC request signing         │    │
│  ├─────────────────────────────┤    │
│  │ Rate limiting                │    │
│  ├─────────────────────────────┤    │
│  │ Input sanitization           │    │
│  │ (no shell, no SQL interp)   │    │
│  └─────────────────────────────┘    │
└─────────────────────────────────────┘
```

All API endpoints call Kotlin services directly — no subprocess execution, no shell commands, no SQL string interpolation.

## Data Flow: Complete Request Lifecycle

```
Browser: POST /api/ask/stream { query, repoPath }
  │
  ├─► ApiRoutes: Create Channel<TraceEvent>, launch orchestrator coroutine
  │
  ├─► Orchestrator.executeWithTrace():
  │     ├── INTENT: qwen3:1.7b classifies → TaskType (e.g., REPO_ANALYSIS)
  │     ├── WORKSPACE: getOrCreate → SQLite DB
  │     ├── SCAN: Walk repo → 459 files (cached after first run)
  │     ├── MODULE_DISCOVERY: IntelliJ modules (if enabled)
  │     ├── CHUNK: Split into 2437 chunks (cached)
  │     ├── EMBED: Generate 768-dim embeddings via nomic-embed-text
  │     ├── EVIDENCE: 15+ detectors → 56 evidence items
  │     ├── CONTEXT_ASSEMBLY: Cosine similarity → top-40 chunks
  │     ├── LLM_CALL:
  │     │     [Standard] chatStream(deepseek-r1:8b) → streaming tokens
  │     │     [Deep Analysis] DeepAnalyzer:
  │     │       ├── Discover 17 modules from evidence
  │     │       ├── Analyze each module (17 LLM calls, streamed)
  │     │       ├── Cache per-module results to disk
  │     │       └── Synthesize final report (1 LLM call, streamed)
  │     ├── VALIDATE: Strip thinking tags, normalize markdown
  │     └── TrainingDataCollector: Record for evolution
  │
  └─► SSE stream: Each TraceEvent → "data: {json}\n\n" → Browser
        Browser: handleTraceEvent() updates trace panel + response area
```

## Runtime Model Switching

ModelSelector supports runtime overrides via a `ConcurrentHashMap<ModelRole, String>`:

```
selectForRole(role):
  1. runtimeOverrides[role]  →  if set and available, use it
  2. config.models[role]     →  YAML primary model
  3. config.models.fallback  →  YAML fallback model
  4. Return override or primary (caller gets Ollama error if missing)
```

Web UI flow:
```
Config tab dropdown onChange
  → POST /api/config/models { role: "REASON", model: "llama3.1:70b" }
  → ModelSelector.setOverride(REASON, "llama3.1:70b")
  → Next query uses llama3.1:70b for all REASON tasks
  → SSE trace shows "model_selected: llama3.1:70b"
```

Overrides are in-memory only (cleared on restart). For permanent changes, edit `forge.yaml`.

## Known Limitations

1. **Embedding model context** — nomic-embed-text BERT has 2048 token limit; chunks > 1800 chars are truncated
2. **In-memory cosine similarity** — For non-IntelliJ repos, all embeddings are loaded into memory for search
3. **SQLite single-writer** — WAL mode allows concurrent reads but only one writer at a time
4. **First run is slow** — Scanning + chunking + embedding a large repo takes minutes
5. **Ollama dependency** — All models must be pulled manually before use

## Solved Issues

| Issue | Cause | Fix |
|-------|-------|-----|
| Evidence key collision | All sub-services stored under key "sub_service" | Path-qualified keys: "sub_service:microservices/alerts" |
| 0 context chunks | Silent exception catch + missing length filter | Log errors + `AND length(embedding) > 0` SQL fix |
| Wrong module names in deep analysis | DeepAnalyzer read evidence keys, not paths | Parse paths from new key format |
| Duplicate evidence records | No unique constraint | `UNIQUE(task_id, category, key, value)` + `INSERT OR IGNORE` |
| LLM tokens not streaming | Used blocking `chat()` instead of `chatStream()` | `executeStreamingLlmCall()` with Flow collection |
| Infinite embedding loop | Failed chunks not marked | `ByteArray(0)` failed marker |
| SQLITE_BUSY under load | No busy timeout | `PRAGMA busy_timeout=30000` |
| OOM on 600K chunks | Global embedding load | Hierarchical two-stage retrieval with lazy embedding |
