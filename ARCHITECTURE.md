# FORGE v2 — Архитектура

## Обзор системы

FORGE — это pipeline-based CLI агент, который принимает пользовательский запрос, проводит его через цепочку стадий (intent resolution → scan → chunk → embed → evidence → context assembly → LLM call), и возвращает ответ, основанный на реальном коде репозитория.

```
┌──────────────────────────────────────────────────────────────────┐
│                        Пользователь                              │
│  forge focus platform/core-api /repo "What EPs does it define?" │
└──────────────────────┬───────────────────────────────────────────┘
                       │
                       ▼
┌──────────────────────────────────────────────────────────────────┐
│                     Main.kt (Clikt CLI)                          │
│  Парсинг аргументов → buildServices() → orchestrator.execute()   │
└──────────────────────┬───────────────────────────────────────────┘
                       │
                       ▼
┌──────────────────────────────────────────────────────────────────┐
│                    Orchestrator.kt                                │
│  1. resolveIntent(userInput) → TaskType                          │
│  2. getOrCreate(workspace)                                       │
│  3. buildPipeline(taskType)                                      │
│  4. Execute stages sequentially                                  │
│  5. Return ForgeResult                                           │
└──────────────────────┬───────────────────────────────────────────┘
                       │
                       ▼
┌──────────────────────────────────────────────────────────────────┐
│                      Pipeline.kt                                 │
│                                                                  │
│  ┌─────────┐  ┌──────────┐  ┌────────────────┐  ┌───────┐      │
│  │ INTENT  │→│ WORKSPACE │→│ SCAN           │→│ CHUNK │       │
│  │ 9-15s   │  │ <1s      │  │ 43ms (cached)  │  │ 253ms │      │
│  └─────────┘  └──────────┘  └────────────────┘  └───────┘      │
│                                                                  │
│  ┌──────────────────┐  ┌───────┐  ┌──────────┐                  │
│  │ MODULE_DISCOVERY │→│ EMBED │→│ EVIDENCE │                   │
│  │ 69ms (cached)    │  │ 0ms*  │  │ 21s      │                  │
│  └──────────────────┘  └───────┘  └──────────┘                  │
│       * IntelliJ mode: skip global embed                         │
│                                                                  │
│  ┌──────────────────────┐  ┌──────────┐  ┌──────────┐           │
│  │ CONTEXT_ASSEMBLY     │→│ LLM_CALL │→│ VALIDATE │           │
│  │ 43-70s (lazy embed)  │  │ 115s     │  │ <1s      │           │
│  └──────────────────────┘  └──────────┘  └──────────┘           │
└──────────────────────────────────────────────────────────────────┘
```

## Ключевые компоненты

### 1. Intent Resolution (`IntentResolver.kt`)

Классифицирует пользовательский запрос в один из 20 `TaskType`:
- Отправляет запрос в classify-модель (`qwen3:1.7b`)
- Получает JSON: `{ "task_type": "EXTENSION_POINT_IMPL", "confidence": 0.85 }`
- TaskType определяет: какую LLM-модель использовать, какие evidence собирать, какой промпт

```kotlin
enum class TaskType(
    val displayName: String,
    val generatesCode: Boolean = false,
    val requiresDeepAnalysis: Boolean = false,
    val modelRole: ModelRole = ModelRole.REASON
)
```

### 2. Workspace & Database (`Database.kt`)

SQLite WAL-mode база с 8 таблицами:

```sql
repos               -- Репозитории (primary + satellites)
modules             -- IntelliJ модули (1438 записей)
files               -- Файлы (180K записей), привязаны к module_id
chunks              -- Чанки кода (598K), с embedding BLOB
chunks_fts          -- FTS5 виртуальная таблица для полнотекстового поиска
evidence            -- Обнаруженные факты о репо (4440 записей)
tasks               -- История задач
extension_points    -- IntelliJ extension points
```

**Критические оптимизации:**
- `PRAGMA journal_mode=WAL` — параллельное чтение/запись
- `PRAGMA busy_timeout=30000` — защита от SQLITE_BUSY
- Batch INSERT через `executeBatch()` (500 файлов за транзакцию)
- `assignFilesToModules()` — SQL LIKE matching: `WHERE relative_path LIKE 'module_name/%'`

### 3. IntelliJ Module System (`intellij/`)

**IntelliJModuleResolver** обнаруживает модули через:
1. `Files.walk()` → находит все `plugin.xml` и `.iml`
2. Для каждого определяет module root (parent directory)
3. Классифицирует по пути:
   - `platform/*-api` → PLATFORM_API
   - `platform/*-impl` → PLATFORM_IMPL
   - `plugins/*` → PLUGIN
   - `lib/*` → LIBRARY
   - `**/test*` → TEST
4. Парсит `plugin.xml` → extension points, services, dependencies

**PluginXmlParser** — tolerant XML parser через `javax.xml.parsers.DocumentBuilderFactory`:
- Извлекает `<extensionPoints>`, `<extensions>`, `<depends>`, `<applicationService>`
- Обрабатывает malformed XML без крашей

### 4. Hierarchical Retriever (`HierarchicalRetriever.kt`)

**Проблема:** 598K чанков невозможно embedding-ить и искать целиком.

**Решение:** двухстадийный поиск:

```
Stage 1: findRelevantModules(query, topK=5)
├── Для каждого модуля: keyword score (совпадение с именем/путём/зависимостями)
├── Если есть summary: embed(query) ⊗ embed(summary) → cosine similarity
└── Rank по max(keyword, summary) → top-5 модулей

Stage 2: findRelevantChunksInModules(query, modules)
├── Lazy embedding: для каждого модуля embed до 1000 чанков (budget)
├── Загрузить до 5000 чанков с эмбеддингами из SQLite
├── Cosine similarity с query embedding
└── Top-20 чанков → context assembly
```

**Ленивое эмбеддирование:**
- Чанки эмбеддятся ТОЛЬКО когда модуль впервые запрашивается
- `ByteArray(0)` маркер для failed chunks — не retry-ятся
- `maxEmbedChars = 1800` — защита от overflow контекста BERT (2048 tokens)

### 5. Evidence Collector (`EvidenceCollector.kt`)

15+ детекторов, запускаемых в зависимости от TaskType:

| Детектор | Что обнаруживает |
|----------|-----------------|
| `detectBuildSystem` | Gradle, Maven, Bazel, npm |
| `detectLanguages` | Топ-5 языков по файлам |
| `detectSourceRoots` | Корневые директории исходников |
| `detectArchitecture` | MVC, microservices, layered |
| `detectConventions` | camelCase vs snake_case, файловые паттерны |
| `detectTestPatterns` | JUnit, TestNG, test-to-source ratio |
| `detectAuthPatterns` | OAuth, JWT, session-based |
| `detectApiEndpoints` | REST, GraphQL, gRPC |
| `detectPsiPatterns` | PsiElement, Parser, Lexer |
| `detectKeyFiles` | Крупнейшие файлы, entry points |
| `detectModuleMap` | Структура модулей |
| `detectCiCd` | GitHub Actions, Jenkins, GitLab CI |

### 6. Context Assembly (`ContextAssembler.kt`)

Token budget management — собирает контекст для LLM:

```
Token Budget: 6000 tokens (~24K chars)
├── Module context (plugin.xml, dependencies): ~500 tokens
├── Evidence (build system, languages, patterns): ~500 tokens
├── Attached files: variable
└── Code chunks: fill remaining budget
```

**Правила:**
- Более релевантные чанки получают больше токенов
- Module context включает: имя, тип, зависимости, extension points
- Evidence включает: языки, архитектуру, паттерны

### 7. Embedding Store (`EmbeddingStore.kt`)

Управляет embedding lifecycle:
- **embed:** `OllamaClient.embed(model, text)` → `Float768Array` → `ByteArray` → SQLite BLOB
- **search:** Load chunks → deserialize BLOB → `FloatArray` → cosine similarity
- **truncation:** `maxEmbedChars = 1800` (nomic-embed-text BERT context = 2048 tokens)
- **failed markers:** `ByteArray(0)` — чанк, который не удалось embedded (не retry)

### 8. LLM Client (`OllamaClient.kt`)

HTTP клиент к Ollama API (`java.net.http.HttpClient`):
- `chat(model, messages, stream)` — генерация ответов
- `embed(model, text)` — embedding (768-dim Float)
- `listModels()` — доступные модели
- Retry logic, timeout handling

## Потоки данных

### Первый запуск (cold start)
```
User: forge focus platform/core-api /repo "What EPs?"
  │
  ├── INTENT: classify("What EPs?") → EXTENSION_POINT_IMPL (9s)
  ├── WORKSPACE: getOrCreate → workspace.db (0.5s)
  ├── SCAN: walkFileTree → 180,505 files → batch INSERT (3 min first time, 43ms cached)
  ├── MODULE_DISCOVERY: discoverModules → 1438 modules → assignFilesToModules (33s first time, 69ms cached)
  ├── CHUNK: split files → 597,984 chunks (30s first time, 253ms cached)
  ├── EMBED: skip (IntelliJ lazy mode) (0ms)
  ├── EVIDENCE: 15 detectors → 4440 evidence records (21s)
  ├── CONTEXT_ASSEMBLY:
  │     ├── findRelevantModules("What EPs?") → [platform/core-api]
  │     ├── getChunksWithoutEmbedding(core-api, limit=1000) → 873 chunks
  │     ├── embedChunksById(873 chunks) → 873 embeddings via Ollama (30s)
  │     ├── findSimilarInModules(query, [core-api], limit=5000) → top-20 chunks
  │     └── assemble(modules, chunks, evidence) → context (43s total)
  ├── LLM_CALL: qwen2.5-coder:14b(context + question) → response (115s)
  └── VALIDATE: clean response (0.1s)
```

### Повторный запуск (warm cache)
```
User: forge focus platform/core-api /repo "Show all services"
  │
  ├── INTENT: 9s
  ├── WORKSPACE: 0.5s
  ├── SCAN: 43ms (cached)
  ├── MODULE_DISCOVERY: 69ms (cached)
  ├── CHUNK: 253ms (cached)
  ├── EMBED: 0ms (skip)
  ├── EVIDENCE: 21s
  ├── CONTEXT_ASSEMBLY: 10s (embeddings cached, only cosine similarity)
  ├── LLM_CALL: 115s
  └── VALIDATE: 0.1s
  Total: ~2.5 min
```

## Схема базы данных

```sql
CREATE TABLE repos (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    local_path TEXT UNIQUE,
    url TEXT,
    branch TEXT,
    is_primary BOOLEAN DEFAULT 1,
    last_scan TEXT,
    commit_sha TEXT
);

CREATE TABLE modules (
    id INTEGER PRIMARY KEY,
    repo_id INTEGER REFERENCES repos(id),
    name TEXT NOT NULL,           -- "platform/core-api"
    path TEXT NOT NULL,           -- absolute path
    plugin_xml TEXT,              -- path to plugin.xml
    module_type TEXT NOT NULL,    -- PLATFORM_API, PLUGIN, etc.
    dependencies TEXT,            -- JSON array
    summary TEXT,                 -- AI-generated summary
    file_count INTEGER DEFAULT 0
);

CREATE TABLE files (
    id INTEGER PRIMARY KEY,
    repo_id INTEGER,
    module_id INTEGER,           -- assigned via LIKE matching
    relative_path TEXT NOT NULL,
    language TEXT,
    category TEXT,               -- source, test, config, build
    size_bytes INTEGER,
    last_modified TEXT,
    content_hash TEXT
);

CREATE TABLE chunks (
    id INTEGER PRIMARY KEY,
    file_id INTEGER REFERENCES files(id),
    chunk_index INTEGER,
    start_line INTEGER,
    end_line INTEGER,
    content TEXT NOT NULL,
    symbol_name TEXT,
    language TEXT,
    embedding BLOB              -- 768 floats × 4 bytes = 3072 bytes
);

CREATE VIRTUAL TABLE chunks_fts USING fts5(
    content, symbol_name, language,
    content=chunks, content_rowid=id
);
```

## Конфигурация (ScaleConfig)

```yaml
scale:
  module_embedding_budget: 1000    # макс новых embeddings на модуль за запрос
  module_top_k: 5                  # сколько модулей выбрать в Stage 1
  similarity_search_limit: 5000    # макс чанков для cosine similarity
  token_budget: 6000               # токен-бюджет для контекста LLM
  parallel_scan_threads: 8         # потоки для параллельного скана
  scan_batch_size: 500             # размер батча для INSERT
```

## Известные ограничения

1. **nomic-embed-text BERT context = 2048 tokens** — чанки длиннее 1800 символов обрезаются
2. **Cosine similarity in-memory** — для 5000 чанков нужно ~15MB RAM, O(5000 × 768) операций
3. **Первый запуск медленный** — сканирование 180K файлов + chunking занимает ~5 минут
4. **SQLite single-writer** — параллельная запись ограничена WAL mode + busy_timeout

## Решённые проблемы (E2E тестирование)

| Баг | Причина | Решение |
|-----|---------|---------|
| Бесконечный цикл embedding | Чанк не помечался как failed | `ByteArray(0)` маркер |
| SQLITE_BUSY | Нет busy_timeout | `PRAGMA busy_timeout=30000` |
| MODULE_DISCOVERY только для 2 типов | Условие в Pipeline.kt | Запуск для ВСЕХ типов |
| Global EMBED на 597K чанков | Нет IntelliJ skip | Lazy per-module в CONTEXT_ASSEMBLY |
| Файлы не привязаны к модулям | Нет assignFilesToModules | SQL UPDATE с LIKE matching |
| file_count = 0 | Нет обновления после assign | UPDATE modules SET file_count = COUNT |
| analyze зависает 30+ мин | 200K embeddings загружаются | LIMIT 5000 + moduleTopK=5 |
| Embedding overflow | 8000 chars > 2048 tokens | maxEmbedChars = 1800 |
