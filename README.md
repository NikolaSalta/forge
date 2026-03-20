# FORGE v2 — Local AI Code Intelligence Platform

**FORGE** (Fast Offline Repository Graph Engine) is a local AI-powered code analysis platform that runs entirely on your machine. It provides deep, evidence-based repository analysis using local LLMs via Ollama — no cloud APIs, no data leaves your machine.

## Key Features

- **Web Dashboard** — Browser-based UI at `http://localhost:3456` with live execution trace
- **Deep Multi-Pass Analysis** — Analyzes each module/service individually, then synthesizes a comprehensive report
- **SSE Live Trace** — Real-time pipeline progress with streaming LLM tokens
- **Evidence-Based** — 15+ detectors collect structural evidence before any LLM call
- **Monorepo Support** — 3-level deep sub-service discovery with per-module analysis
- **IntelliJ Platform Mode** — Specialized support for IntelliJ-scale codebases (180K+ files)
- **Prompt Decomposition** — Complex prompts automatically split into parallel partitions
- **Model Evolution** — Collects training data from usage for future model fine-tuning
- **Fully Local** — Ollama inference, SQLite storage, no external network calls

## Quick Start

### Requirements
- **JDK 21+** (tested on Temurin)
- **Ollama** running locally (`http://127.0.0.1:11434`)
- Required models: `qwen2.5-coder:14b`, `nomic-embed-text`, `qwen3:1.7b`, `deepseek-r1:8b`

### Install & Run

```bash
git clone https://github.com/NikolaSalta/forge.git
cd forge
./gradlew run
```

Open `http://localhost:3456` in your browser.

### CLI Usage

```bash
# Build CLI binary
./gradlew clean build installDist

# Full repository analysis
./build/install/forge/bin/forge analyze /path/to/repo

# Ask a question about a repo
./build/install/forge/bin/forge ask /path/to/repo "How does authentication work?"

# Interactive REPL
./build/install/forge/bin/forge shell /path/to/repo

# Focus on specific IntelliJ module
./build/install/forge/bin/forge focus platform/core-api /path/to/intellij-community "What extension points?"

# List IntelliJ modules
./build/install/forge/bin/forge modules /path/to/repo

# System status
./build/install/forge/bin/forge status
```

## Web Dashboard

The primary interface is a 4-tab web dashboard served by an embedded Ktor server:

| Tab | Description |
|-----|-------------|
| **Chat** | Send queries, see live execution trace with progress bar, streaming LLM response |
| **Evolution** | View collected training data, model quality scores, evolution plans |
| **Config** | View current configuration, model assignments, retrieval settings |
| **About** | System info, workspace list, Ollama status |

### Live Execution Trace

Every query shows a real-time pipeline trace:
```
✓ INTENT         Resolved: Repository Analysis (confidence: 0.90)     15.4s
✓ WORKSPACE      Using workspace at ~/.forge/workspaces/de49...       14ms
✓ SCAN           Scanned 459 files                                    201ms
✓ CHUNK          Created 2437 chunks                                  911ms
✓ EMBED          Embedded 2437 of 2437 chunks                         62.9s
✓ EVIDENCE       Collected 56 evidence items                          159ms
✓ CONTEXT        Assembled 40 context chunks via embedding search     24ms
■ Deep Analysis  microservices/alerts (3/17)                          ████░░ 18%
```

## Deep Multi-Pass Analysis

For repository analysis tasks, FORGE doesn't make a single LLM call. Instead:

1. **Discover modules** — from MONOREPO_STRUCTURE evidence or directory structure
2. **Analyze each module** — read all source files, send to LLM with deep analysis prompt
3. **Cache results** — write per-module analysis to `~/.forge/workspaces/{hash}/analysis/`
4. **Synthesize** — combine all module analyses into one comprehensive report

A 17-service monorepo gets 18 LLM calls instead of 1, producing class-level, method-level, and dependency-level detail.

## Pipeline Architecture

Every query flows through a configurable pipeline:

```
INTENT → WORKSPACE → SCAN → MODULE_DISCOVERY → CHUNK → EMBED → EVIDENCE → CONTEXT_ASSEMBLY → LLM_CALL → VALIDATE
```

For deep analysis tasks (REPO_ANALYSIS, PROJECT_OVERVIEW, ARCHITECTURE_REVIEW), the LLM_CALL stage is replaced by DeepAnalyzer:

```
... → EVIDENCE → CONTEXT_ASSEMBLY → [DeepAnalyzer: per-module analysis × N + synthesis] → VALIDATE
```

## Configuration

Default config in `forge-default.yaml`. Override with `forge.yaml`:

```yaml
ollama:
  host: "http://127.0.0.1:11434"
  timeout_seconds: 300

models:
  classify: "qwen3:1.7b"        # Intent classification
  reason: "deepseek-r1:8b"      # Reasoning / analysis
  code: "qwen2.5-coder:14b"     # Code generation
  embed: "nomic-embed-text"     # Embeddings (768-dim)
  summarize: "ministral-3:8b"   # Summarization
  vision: "qwen2.5vl:7b"        # Image understanding
  fallback:
    classify: "deepseek-r1:1.5b"
    reason: "qwen2.5:14b"
    code: "qwen2.5-coder:7b"
    summarize: "qwen3:1.7b"

retrieval:
  max_context_chunks: 40
  similarity_threshold: 0.50
  embedding_batch_size: 10

scale:
  token_budget: 16000
  module_embedding_budget: 1000
  module_top_k: 5
  similarity_search_limit: 5000

intellij:
  enabled: true                  # Enable for IntelliJ-scale repos
```

## Evidence System

15+ detectors collect structural facts before any LLM call:

| Detector | Discovers |
|----------|-----------|
| Build System | Gradle, Maven, npm, pip, Cargo |
| Languages | Top languages by file count |
| Monorepo Structure | 3-level deep sub-service paths with build/docker/lang info |
| Source Roots | Entry points, main directories |
| Architecture | MVC, microservices, layered, modular monolith |
| CI/CD | GitHub Actions, Jenkins, GitLab CI |
| API Endpoints | REST, GraphQL, gRPC patterns |
| Auth Patterns | OAuth, JWT, session-based |
| Test Patterns | JUnit, pytest, jest, test-to-source ratio |
| Key Files | Largest files, config files, entry points |
| Docker Compose | Service names from docker-compose.yml |

Evidence is stored with path-qualified keys (schema v2) to prevent key collisions in monorepos.

## Prompt Decomposition

Complex multi-part prompts are automatically decomposed:

```
PromptAnalyzer → ExecutionPlanner → ParallelExecutor → Reconciler → ResultSynthesizer
```

1. **Analyze** — Classify prompt complexity (SIMPLE / COMPOUND / MULTI_STAGE)
2. **Plan** — Build DAG of dependent partitions
3. **Execute** — Run partitions in parallel with semaphore-limited concurrency
4. **Reconcile** — Detect contradictions between partition results
5. **Synthesize** — Merge all results into a unified response

## Model Evolution

FORGE collects training data from every interaction for future model fine-tuning:

- **TrainingDataCollector** — Records prompt + response + quality score per session
- **QualityScorer** — Evaluates response quality (evidence coverage, specificity, completeness)
- **TrainingDataFilter** — PII filtering before dataset export
- **DatasetBuilder** — Export training pairs in fine-tuning format
- **ModelEvolutionPlanner** — Plans when to fine-tune based on data volume + quality

## Security

The embedded web server includes:
- **Localhost-only binding** — `127.0.0.1:3456`, not accessible from network
- **HMAC request signing** — Prevents replay attacks
- **CORS restrictions** — Only localhost origins allowed
- **Input sanitization** — No shell commands, no SQL string interpolation
- **Rate limiting** — Configurable request throttling

## Project Structure

```
src/main/kotlin/forge/
├── Config.kt                           # Configuration (YAML parsing)
├── Main.kt                             # CLI entry point + web server startup
├── core/
│   ├── DeepAnalyzer.kt                 # Multi-pass deep repository analysis
│   ├── IntentResolver.kt               # Task classification via LLM
│   ├── Orchestrator.kt                 # Pipeline execution engine + SSE streaming
│   ├── Pipeline.kt                     # Pipeline stage definitions
│   ├── StateManager.kt                 # Pause/Stop state
│   ├── TaskType.kt                     # 20 task types
│   └── prompt/
│       ├── PromptAnalyzer.kt           # Prompt complexity analysis
│       ├── ExecutionPlanner.kt         # DAG-based execution planning
│       ├── ParallelExecutor.kt         # Parallel partition execution
│       ├── Reconciler.kt              # Cross-partition reconciliation
│       └── ResultSynthesizer.kt       # Final result synthesis
├── evolution/
│   ├── TrainingDataCollector.kt        # Training data collection
│   ├── QualityScorer.kt               # Response quality scoring
│   ├── TrainingDataFilter.kt          # PII filtering
│   ├── DatasetBuilder.kt             # Fine-tuning dataset export
│   ├── LocalModelRegistry.kt         # Local model management
│   └── ModelEvolutionPlanner.kt       # Evolution planning
├── web/
│   ├── ForgeServer.kt                 # Embedded Ktor server
│   ├── ApiRoutes.kt                   # REST API endpoints
│   ├── TraceEvent.kt                  # SSE event types
│   ├── Security.kt                    # HMAC, CORS, rate limiting
│   ├── WebState.kt                    # Web UI state management
│   └── WebModels.kt                   # API request/response models
├── intellij/
│   ├── IntelliJModuleResolver.kt      # Module discovery
│   └── PluginXmlParser.kt            # plugin.xml parsing
├── llm/
│   ├── ContextAssembler.kt            # Token budget management
│   ├── ModelSelector.kt               # Model selection per task
│   ├── OllamaClient.kt               # Ollama HTTP client (sync + streaming)
│   ├── PromptBuilder.kt              # Prompt construction + evidence prioritization
│   └── ResponseParser.kt             # Response cleaning (thinking tags, markdown)
├── retrieval/
│   ├── Chunker.kt                     # Semantic code chunking
│   ├── EvidenceCollector.kt           # 15+ evidence detectors
│   ├── HierarchicalRetriever.kt       # Two-stage module→chunk search
│   └── RepoScanner.kt                # Parallel file scanning
├── workspace/
│   ├── Database.kt                    # SQLite (WAL, FTS5, 8 tables)
│   ├── EmbeddingStore.kt             # Embedding generation + similarity search
│   └── WorkspaceManager.kt           # Workspace lifecycle
├── ui/
│   ├── ForgeConsole.kt               # Rich terminal output
│   └── ReplShell.kt                  # Interactive REPL
├── files/                             # File extractors (PDF, DOCX, XLSX)
└── voice/                             # Whisper voice input

src/main/resources/
├── forge-default.yaml                 # Default configuration
├── prompts/                           # LLM prompt templates
│   ├── classify_system.txt / classify_user.txt
│   ├── repo_analysis.txt / repo_analysis_system.txt
│   ├── system.txt / task_user.txt
│   └── intent_classification.txt
└── web/
    └── index.html                     # Single-page web dashboard
```

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Language | Kotlin 2.3.0 |
| Runtime | JVM 21+ |
| Build | Gradle Kotlin DSL |
| Web Server | Ktor (Netty) with SSE |
| LLM | Ollama API (local inference) |
| Embeddings | nomic-embed-text (768-dim BERT) |
| Database | SQLite WAL + FTS5 |
| CLI | Clikt |
| Terminal | Mordant |

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/current-path` | Get current repo path |
| POST | `/api/set-repo` | Set repository to analyze |
| POST | `/api/ask/stream` | Send query with SSE streaming response |
| GET | `/api/workspaces` | List all workspaces |
| DELETE | `/api/workspaces/{hash}` | Delete a workspace |
| GET | `/api/ollama/models` | List available Ollama models |
| GET | `/api/config` | Get current configuration |
| GET | `/api/evolution/data` | Get training data |
| GET | `/api/evolution/plan` | Get evolution plan |

## License

MIT
