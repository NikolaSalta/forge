# AI Prompt для анализа проекта FORGE

> Скопируйте этот промпт целиком и вставьте его в Cursor, Windsurf, Claude Code, ChatGPT или любую другую AI IDE, чтобы она полностью разобралась в проекте.

---

## Промпт

```
Ты — эксперт-разработчик, которому нужно полностью разобраться в проекте FORGE. Это CLI-агент для анализа масштабных кодовых баз с использованием локальных LLM (Ollama). Проект написан на Kotlin 2.3.0 / JVM 21 / Gradle.

## Твоя задача

Прочитай все файлы проекта, пойми архитектуру, потоки данных и зависимости между компонентами. После этого ты сможешь отвечать на любые вопросы по коду, исправлять баги, добавлять фичи.

## Структура проекта (59 файлов, ~12,200 строк Kotlin)

### Точка входа и CLI
- `src/main/kotlin/forge/Main.kt` (730 строк) — 10 CLI-команд на Clikt:
  - `analyze <path>` — полный анализ репозитория
  - `ask <path> <question>` — вопрос к репо (+ голосовой ввод через Whisper)
  - `shell <path>` — интерактивный REPL
  - `focus <module> <path> <question>` — запрос внутри конкретного IntelliJ модуля
  - `modules <path>` — список модулей (--filter, --type)
  - `connect <repo> <path>` — подключить сателлитный репо
  - `status`, `models`, `clear`, `voice-setup`

  Все команды создают `ForgeServices` через `buildServices(config)` и вызывают `orchestrator.execute()`.

### Конфигурация
- `src/main/kotlin/forge/Config.kt` (~270 строк) — data classes для всех настроек:
  - `ForgeConfig` — корневой, содержит: `OllamaConfig`, `ModelsConfig`, `WorkspaceConfig`, `RetrievalConfig`, `VoiceConfig`, `UiConfig`, `IntellijConfig`, `MultiRepoConfig`, `ScaleConfig`
  - `ScaleConfig` — критические параметры производительности: `moduleTopK=5`, `moduleEmbeddingBudget=1000`, `similaritySearchLimit=5000`, `tokenBudget=6000`
  - YAML парсинг через SnakeYAML
- `src/main/resources/forge-default.yaml` — дефолтные значения всех настроек

### Ядро (core/)
- `Orchestrator.kt` (224 строки) — главный оркестратор:
  1. `resolveIntentSafely(userInput)` → TaskType
  2. `workspaceManager.getOrCreate(repoPath)` → Workspace + Database
  3. `buildPipeline(taskType)` → список PipelineStage
  4. Последовательное выполнение stages с trace-записью
  5. Возврат `ForgeResult(response, taskType, model, trace)`

- `Pipeline.kt` (359 строк) — 7 стадий pipeline:
  1. **SCAN** — `RepoScanner.scan()` → 180K файлов в SQLite
  2. **MODULE_DISCOVERY** — `IntelliJModuleResolver.discoverModules()` → 1438 модулей
     - Также `assignFilesToModules()` — SQL LIKE matching файлов к модулям
  3. **CHUNK** — `Chunker.chunkAll()` → 598K семантических чанков
  4. **EMBED** — пропускается в IntelliJ mode (lazy embedding в CONTEXT_ASSEMBLY)
  5. **EVIDENCE** — `EvidenceCollector.collect()` → 15+ детекторов
  6. **CONTEXT_ASSEMBLY** — два пути:
     - IntelliJ mode: `HierarchicalRetriever.search()` (двухстадийный module→chunk)
     - Standard: `EmbeddingStore.findSimilar()` (глобальный embedding search)
     - Затем `ContextAssembler.assemble()` с token budget
  7. **LLM_CALL** — `OllamaClient.chat()` через HTTP к Ollama
  8. **VALIDATE** — strip thinking tags, validate code blocks

- `IntentResolver.kt` — классификация запроса через LLM → один из 20 TaskType
- `TaskType.kt` — 20 типов задач (REPO_ANALYSIS, IMPLEMENT_FEATURE, BUG_ANALYSIS, PLUGIN_DEVELOPMENT, EXTENSION_POINT_IMPL, и т.д.)
- `StateManager.kt` — pause/stop через Ctrl+C

### IntelliJ модульная система (intellij/)
- `IntelliJModuleResolver.kt` — обнаружение модулей через plugin.xml и .iml файлы:
  - `discoverModules()` — `Files.walk()` → найти все plugin.xml/iml → classify по пути
  - `persistToDatabase()` — сохранить модули + extension points в БД
  - Классификация: PLATFORM_API, PLATFORM_IMPL, PLUGIN, LIBRARY, COMMUNITY, TEST
- `PluginXmlParser.kt` — XML парсер через javax.xml (без внешних зависимостей)
  - Извлекает: extension points, services, dependencies, extensions
- `IntelliJPatterns.kt` — regex-паттерны IntelliJ API (Action, Intention, Inspection, PsiElement)

### Иерархический поиск (retrieval/)
- `HierarchicalRetriever.kt` (177 строк) — КЛЮЧЕВОЙ КОМПОНЕНТ:
  - **Stage 1: `findRelevantModules(query, topK=5)`**
    - Keyword scoring: name(3x), path(2x), type(1x), dependencies(1x)
    - Summary scoring: term overlap с AI-summary модуля
    - Возвращает top-5 модулей
  - **Stage 2: `findRelevantChunksInModules(query, modules)`**
    - Lazy embedding: для каждого модуля embed до 1000 чанков (budget)
    - `findSimilarInModules()` — загрузка до 5000 чанков, cosine similarity
    - Возвращает top-20 чанков
  - `searchInModule()` — focus mode: поиск только в одном модуле

- `EvidenceCollector.kt` (~1200 строк) — 15+ детекторов:
  - Build systems, languages, source/test roots, architecture, conventions
  - Auth patterns, API endpoints, CI/CD, PSI patterns
  - Каждый детектор: `db.getFilesByCategory()` → анализ → `db.insertEvidence()`

- `RepoScanner.kt` — параллельное сканирование через `Files.walkFileTree()`
- `Chunker.kt` — семантическая разбивка файлов (80 строк/чанк, 10 overlap)
- `DependencyMapper.kt` — граф зависимостей через import parsing

### Workspace и БД (workspace/)
- `Database.kt` (1288 строк) — SQLite WAL с 8 таблицами:
  ```
  repos → modules → files → chunks (+ embedding BLOB)
                                   → chunks_fts (FTS5)
  tasks → evidence
  modules → extension_points → ep_implementations
  ```
  - `PRAGMA journal_mode=WAL` + `PRAGMA busy_timeout=30000`
  - Batch INSERT: `insertFilesBatch()`, `insertChunksBatch()`
  - `assignFilesToModules()` — SQL UPDATE с LIKE matching по relative_path
  - `getChunksWithEmbeddingsByModules(moduleIds, limit)` — UNION ALL с per-module limit
  - Все методы `synchronized(lock)` для thread safety

- `EmbeddingStore.kt` (~300 строк) — управление эмбеддингами:
  - `maxEmbedChars = 1800` (nomic-embed-text BERT context = 2048 tokens)
  - `embedChunksById()` — lazy embedding по ID, failed → ByteArray(0) маркер
  - `findSimilarInModules()` — загрузка + cosine similarity
  - `rankChunks()` — глобальный поиск для non-IntelliJ mode

- `WorkspaceManager.kt` — создание/поиск workspace по SHA256 пути
- `MultiRepoManager.kt` — мульти-репо: register/list satellites

### LLM интеграция (llm/)
- `OllamaClient.kt` — HTTP клиент к Ollama API (java.net.http):
  - `chat(model, messages, stream)` → String response
  - `embed(model, text)` → FloatArray (768-dim)
  - `listModels()`, `showModel()`, `isAvailable()`
- `ModelSelector.kt` — выбор модели по TaskType.modelRole (CODE/REASON/CLASSIFY)
- `PromptBuilder.kt` — сборка системных + user промптов из шаблонов
- `ContextAssembler.kt` — token budget management (6000 tokens)
- `ResponseParser.kt` — очистка ответов LLM (thinking tags, code blocks)

### UI (ui/)
- `ForgeConsole.kt` — Mordant rich terminal (banner, tables, colors, progress)
- `ReplShell.kt` — интерактивный REPL (/focus, /modules, /repos, /help, /clear)
- `TraceDisplay.kt` — визуализация execution trace

### Файловые экстракторы (files/)
- `FileProcessor.kt`, `PdfExtractor.kt`, `DocxExtractor.kt`, `XlsxExtractor.kt`, `CsvExtractor.kt`, `ImageProcessor.kt`, `TextExtractor.kt`
- PDF: PDFBox, DOCX/XLSX: Apache POI, HTML: JSoup, Images: Ollama vision model

### Голосовой ввод (voice/)
- `VoiceInput.kt` — запись аудио через javax.sound.sampled
- `WhisperTranscriber.kt` — транскрипция через Whisper JNI
- `VoiceConfig.kt` — настройки (язык, silence detection)

## Зависимости (build.gradle.kts)
- Kotlin 2.3.0, JVM 21
- Clikt 5.0.3 (CLI), Mordant 3.0.2 (terminal)
- SQLite JDBC 3.47.1.0, Kotlinx Coroutines 1.10.1
- Kotlinx Serialization JSON 1.8.0, SnakeYAML 2.3
- Whisper JNI 1.7.1, PDFBox 3.0.3, POI 5.2.5, JSoup 1.18.3

## Ключевые архитектурные решения

1. **Pipeline-based execution** — каждый запрос проходит через цепочку стадий
2. **Hierarchical retrieval** — двухстадийный module→chunk поиск для 600K+ чанков
3. **Lazy per-module embedding** — эмбеддинги только по запросу, не для всех 600K чанков
4. **ByteArray(0) failed markers** — чанки, которые не удалось embedded, не retry-ятся
5. **SQLite WAL + FTS5** — параллельное чтение, полнотекстовый поиск
6. **Batch operations** — INSERT батчами по 500 для 180K файлов
7. **Token budget management** — контекст для LLM собирается в рамках бюджета 6000 токенов
8. **Multi-repo satellites** — подключение дополнительных репозиториев для unified search

## Критические пути кода (hot paths)

### forge focus platform/core-api /repo "What EPs?"
```
Main.FocusCommand.run()
  → buildServices(config)
  → orchestrator.execute(userInput, repoPath, focusModule="platform/core-api")
    → IntentResolver.resolve() → EXTENSION_POINT_IMPL
    → Pipeline stages:
      SCAN → MODULE_DISCOVERY → CHUNK → EMBED(skip) → EVIDENCE
      → CONTEXT_ASSEMBLY:
        HierarchicalRetriever.searchInModule("platform/core-api")
          → db.getModuleByName("platform/core-api")
          → db.getChunksWithoutEmbeddingsByModule(moduleId, 1000)
          → EmbeddingStore.embedChunksById(chunkIds) [lazy]
          → EmbeddingStore.findSimilarInModules(query, [moduleId], limit=5000)
            → db.getChunksWithEmbeddingsByModules([moduleId], 5000)
            → cosine similarity → top-20 chunks
        ContextAssembler.assemble(modules, chunks, evidence)
      → LLM_CALL: OllamaClient.chat(qwen2.5-coder:14b, prompt)
      → VALIDATE: ResponseParser.clean()
    → ForgeResult
```

### Поток данных в БД
```
RepoScanner.scan() → files table (180K rows)
Chunker.chunkAll() → chunks table (598K rows)
IntelliJModuleResolver → modules table (1438 rows) + extension_points
Database.assignFilesToModules() → UPDATE files SET module_id
EmbeddingStore.embedChunksById() → UPDATE chunks SET embedding (BLOB)
EvidenceCollector.collect() → evidence table (4440 rows)
```

## Как запустить
```bash
./gradlew clean build installDist
./build/install/forge/bin/forge status
./build/install/forge/bin/forge modules /path/to/intellij-community --type PLATFORM_API
./build/install/forge/bin/forge focus platform/core-api /path/to/intellij-community "What extension points?"
```

## Что нужно знать для работы с кодом

1. **Все DB-операции synchronized** — Database.kt использует `synchronized(lock)` на каждом методе
2. **Embedding = ByteArray** — 768 float × 4 bytes = 3072 bytes BLOB в SQLite
3. **Failed embedding = ByteArray(0)** — маркер "не retry-ить"
4. **maxEmbedChars = 1800** — защита от overflow BERT context (2048 tokens)
5. **Config cascade** — forge-default.yaml (в JAR) < forge.yaml (рядом с binary) < CLI args
6. **IntelliJ mode** — `config.intellij.enabled` переключает между hierarchical и global retrieval
7. **PipelineContext** — mutable объект, передаётся через все stages, каждый stage добавляет свои поля
8. **Coroutines** — Orchestrator.execute() и HierarchicalRetriever.search() — suspend functions
9. **ForgeConsole** — Mordant wrapper, не println. Методы: info(), error(), warn(), success(), result()
10. **Module assignment** — `WHERE relative_path LIKE 'module_name/%'`, sorted by name length DESC

Прочитай все указанные файлы и будь готов отвечать на вопросы по архитектуре, исправлять баги и добавлять новые фичи.
```

---

## Использование

### В Cursor / Windsurf
1. Откройте проект `/Users/nikolay/Desktop/NEW_IDE/forge`
2. Вставьте промпт выше в чат
3. IDE прочитает все файлы и будет готова к работе

### В Claude Code
```bash
cd /path/to/forge
# Вставьте промпт в первое сообщение
```

### В ChatGPT / Claude Web
1. Скопируйте промпт
2. Прикрепите архив проекта или ключевые файлы
3. ChatGPT/Claude разберётся в архитектуре

### Примеры вопросов после загрузки промпта

- "Как работает hierarchical retrieval? Покажи полный flow от запроса пользователя до ответа LLM"
- "Найди все места, где используется moduleEmbeddingBudget, и объясни как он влияет на производительность"
- "Добавь новую CLI команду `forge diff` которая показывает изменения с последнего скана"
- "Оптимизируй cosine similarity search — сейчас он загружает все 5000 чанков в память"
- "Почему forge analyze медленнее чем forge focus? Как это исправить?"
- "Добавь поддержку нового embedding model (e5-large) с размерностью 1024"
