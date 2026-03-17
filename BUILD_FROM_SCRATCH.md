# Промпт для создания FORGE с нуля

> Скопируйте этот промпт целиком в Cursor, Windsurf, Claude Code или другую AI IDE.
> AI создаст полный проект FORGE — локальный AI-агент для анализа кодовых баз через Ollama.

---

```
Создай с нуля полноценный CLI-инструмент "FORGE" (Fast Offline Repository Graph Engine) — локальный AI-агент для анализа масштабных кодовых баз с использованием Ollama (локальные LLM).

## Требования к окружению
- Kotlin 2.3.0, JVM 21, Gradle Kotlin DSL
- Ollama запущен на http://127.0.0.1:11434
- Модели: qwen2.5-coder:14b (код), nomic-embed-text (эмбеддинги), qwen3:1.7b (классификация)
- macOS/Linux

## Зависимости (build.gradle.kts)
```kotlin
plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.serialization") version "2.3.0"
    application
}

group = "dev.forge"
version = "0.1.0"

application {
    mainClass.set("forge.MainKt")
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation("com.github.ajalt.clikt:clikt:5.0.3")         // CLI
    implementation("com.github.ajalt.mordant:mordant-omnibus:3.0.2") // Rich terminal
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("org.yaml:snakeyaml:2.3")                      // Config
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")             // Database
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    implementation("io.github.givimad:whisper-jni:1.7.1")         // Voice
    implementation("org.apache.pdfbox:pdfbox:3.0.3")              // PDF
    implementation("org.apache.poi:poi-ooxml:5.2.5")              // Word/Excel
    implementation("org.jsoup:jsoup:1.18.3")                      // HTML
}
```

## Архитектура

### 1. Pipeline-based execution
Каждый запрос пользователя проходит через pipeline из 8 стадий:

```
INTENT → SCAN → MODULE_DISCOVERY → CHUNK → EMBED → EVIDENCE → CONTEXT_ASSEMBLY → LLM_CALL → VALIDATE
```

Каждая стадия — это `PipelineStage(name, description, execute: suspend (PipelineContext) -> Unit)`.
`PipelineContext` — mutable объект, который передаётся через все стадии. Каждая стадия читает входные данные и записывает результаты в контекст.

### 2. Главные компоненты (создай каждый как отдельный .kt файл)

#### 2.1 Config.kt — Конфигурация
```kotlin
data class ForgeConfig(
    val ollama: OllamaConfig,       // host, timeout, retry
    val models: ModelsConfig,        // classify, reason, code, embed, summarize модели
    val workspace: WorkspaceConfig,  // base_dir, max_file_size, chunk_size
    val retrieval: RetrievalConfig,  // max_context_chunks, similarity_threshold, scan_ignore
    val voice: VoiceConfig,          // whisper model, language, silence detection
    val ui: UiConfig,                // show_trace, show_evidence, streaming
    val intellij: IntellijConfig,    // enabled, module_priorities, skip_modules
    val multiRepo: MultiRepoConfig,  // satellites, auto_clone, clone_base_dir
    val scale: ScaleConfig           // moduleTopK, moduleEmbeddingBudget, similaritySearchLimit, tokenBudget
)
```
Парсинг из YAML через SnakeYAML. Создай `forge-default.yaml` в ресурсах с дефолтными значениями.

#### 2.2 Main.kt — CLI (Clikt)
10 subcommands:
- `forge analyze <path>` — полный анализ репозитория
- `forge ask <path> <question> [--voice] [--file]` — вопрос с опциональным голосовым вводом
- `forge shell <path>` — интерактивный REPL
- `forge focus <module> <path> <question>` — запрос внутри конкретного модуля
- `forge modules <path> [--filter] [--type]` — список IntelliJ модулей
- `forge connect <repo> <path>` — подключить сателлитный репо
- `forge status` — статус Ollama, workspace, конфигурации
- `forge models` — доступные модели и роли
- `forge clear [path]` — очистить workspace
- `forge voice-setup [--model]` — скачать Whisper модель

Каждая команда создаёт `ForgeServices` (OllamaClient, WorkspaceManager, ModelSelector, PromptBuilder, Orchestrator) и вызывает `orchestrator.execute()`.

#### 2.3 OllamaClient.kt — HTTP клиент к Ollama
```kotlin
class OllamaClient(host: String, timeoutSeconds: Int) {
    suspend fun chat(model: String, messages: List<Message>, stream: Boolean = false): String
    suspend fun embed(model: String, text: String): FloatArray  // 768-dim для nomic-embed-text
    suspend fun listModels(): List<ModelInfo>
    fun isAvailable(): Boolean
}
```
Использует `java.net.http.HttpClient`. Для `chat` — POST `/api/chat`, для `embed` — POST `/api/embeddings`. JSON через kotlinx.serialization.

#### 2.4 Database.kt — SQLite WAL
8 таблиц:
```sql
repos       (id, name, local_path, url, branch, is_primary, last_scan, commit_sha)
modules     (id, repo_id, name, path, plugin_xml, module_type, dependencies JSON, summary, file_count)
files       (id, repo_id, module_id, relative_path, language, category, size_bytes, content_hash)
chunks      (id, file_id, chunk_index, start_line, end_line, content TEXT, symbol_name, language, embedding BLOB)
chunks_fts  -- FTS5 virtual table на chunks.content
evidence    (id, task_id, category, key, value, confidence, source_file)
tasks       (id, type, intent, status, model_used, repo_path, result_summary)
extension_points (id, module_id, qualified_name, interface_fqn, bean_class, area)
```

КРИТИЧЕСКИЕ оптимизации:
- `PRAGMA journal_mode=WAL` + `PRAGMA busy_timeout=30000`
- Batch INSERT через `executeBatch()` (500 файлов за транзакцию)
- Все методы `synchronized(lock)` для thread safety
- `assignFilesToModules()` — `UPDATE files SET module_id = ? WHERE relative_path LIKE 'moduleName/%'` (sorted by name length DESC для правильного matching)

#### 2.5 Orchestrator.kt
```kotlin
class Orchestrator(config, ollamaClient, workspaceManager, ...) {
    suspend fun execute(userInput: String, repoPath: Path, focusModule: String? = null): ForgeResult {
        // 1. resolveIntent(userInput) → TaskType (через LLM classification)
        // 2. getOrCreate(workspace)
        // 3. buildPipeline(taskType) → List<PipelineStage>
        // 4. Execute stages sequentially, record trace
        // 5. Return ForgeResult(response, taskType, model, trace)
    }
}
```

#### 2.6 Pipeline.kt — Стадии
```kotlin
fun buildPipeline(taskType: TaskType): List<PipelineStage> {
    // SCAN: RepoScanner.scan() → files в БД
    // MODULE_DISCOVERY: IntelliJModuleResolver.discoverModules() → modules в БД
    //   + assignFilesToModules()
    // CHUNK: Chunker.chunkAll() → chunks в БД (80 строк/чанк, 10 overlap)
    // EMBED: skip в IntelliJ mode (lazy embedding в CONTEXT_ASSEMBLY)
    // EVIDENCE: EvidenceCollector.collect() → evidence в БД
    // CONTEXT_ASSEMBLY:
    //   if (intellij.enabled): HierarchicalRetriever.search() — двухстадийный поиск
    //   else: EmbeddingStore.findSimilar() — глобальный поиск
    //   + ContextAssembler.assemble() — token budget management
    // LLM_CALL: OllamaClient.chat(selectedModel, prompt)
    // VALIDATE: ResponseParser.clean()
}
```

#### 2.7 HierarchicalRetriever.kt — КЛЮЧЕВОЙ КОМПОНЕНТ
Двухстадийный поиск для масштабных кодовых баз (600K+ чанков):

```kotlin
class HierarchicalRetriever(config, ollamaClient, db) {
    // Stage 1: findRelevantModules(query, topK=5)
    //   - Keyword scoring: module name (3x), path (2x), type (1x), dependencies (1x)
    //   - Нормализация к [0,1], фильтр > 0.05, top-K

    // Stage 2: findRelevantChunksInModules(query, modules)
    //   - Lazy embedding: для каждого модуля embed до budget=1000 чанков
    //     - Если embedding fails → записать ByteArray(0) как маркер (не retry)
    //     - Truncate content до maxEmbedChars=1800 перед embedding
    //   - findSimilarInModules: загрузить до 5000 чанков с embeddings
    //   - Cosine similarity с query embedding
    //   - Top-20 чанков → context assembly

    // search(): Stage 1 → Stage 2 → RetrievalResult
    // searchInModule(moduleName): прямой поиск в одном модуле (focus mode)
}
```

#### 2.8 EmbeddingStore.kt
```kotlin
class EmbeddingStore(ollamaClient, db, config) {
    private val maxEmbedChars = 1800  // BERT context = 2048 tokens, code ≈ 1-3 chars/token

    suspend fun embedChunksById(chunkIds: List<Int>) {
        // Для каждого chunk: truncate → embed → updateChunkEmbedding
        // При ошибке: записать ByteArray(0) маркер (failed, не retry)
    }

    suspend fun findSimilarInModules(query, moduleIds, topK=20, threshold=0.65): List<ScoredChunk> {
        // 1. embed(query) → queryEmbedding
        // 2. db.getChunksWithEmbeddingsByModules(moduleIds, limit=5000)
        // 3. Для каждого chunk: cosine similarity с queryEmbedding
        // 4. Filter >= threshold, sort desc, take topK
    }

    // cosineSimilarity(a, b): dot product / (norm(a) * norm(b))
}
```

#### 2.9 IntelliJModuleResolver.kt
```kotlin
class IntelliJModuleResolver(repoRoot: Path) {
    fun discoverModules(): List<IntelliJModule> {
        // 1. Files.walk() → найти все plugin.xml и .iml
        // 2. Для каждого: определить module root (parent dir)
        // 3. Классифицировать по пути:
        //    platform/*-api → PLATFORM_API
        //    platform/*-impl → PLATFORM_IMPL
        //    plugins/* → PLUGIN
        //    lib/* → LIBRARY
        //    **/test* → TEST
        // 4. Парсить plugin.xml → extension points, services, depends
    }

    fun persistToDatabase(modules, repoId, db) {
        // INSERT modules + extension_points
    }
}
```

#### 2.10 PluginXmlParser.kt
XML парсер через `javax.xml.parsers.DocumentBuilderFactory` (в JDK):
- `<extensionPoints>` → ExtensionPointDecl
- `<extensions>` → ExtensionImpl
- `<depends>` → зависимости
- `<applicationService>`, `<projectService>` → ServiceDecl
- Tolerant к malformed XML (try-catch, не crash)

#### 2.11 EvidenceCollector.kt
15+ детекторов, каждый анализирует файлы из БД:
- `detectBuildSystem` — Gradle/Maven/Bazel/npm по файлам
- `detectLanguages` — top-5 языков по расширениям
- `detectSourceRoots` — корневые директории
- `detectArchitecture` — MVC/microservices/layered по структуре
- `detectConventions` — camelCase vs snake_case
- `detectTestPatterns` — JUnit/TestNG, test-to-source ratio
- `detectAuthPatterns` — OAuth/JWT/session (сканирование файлов)
- `detectApiEndpoints` — REST/GraphQL/gRPC
- `detectPsiPatterns` — PsiElement/Parser/Lexer (IntelliJ-specific)
- `detectKeyFiles` — крупнейшие файлы, entry points

Каждый: `db.getFilesByCategory("source")` → анализ → `db.insertEvidence(taskId, category, key, value)`

#### 2.12 ContextAssembler.kt
```kotlin
class ContextAssembler(config) {
    fun assemble(relevantModules, scoredChunks, evidence, attachedFiles, focusModule): AssembledContext {
        // Token budget: 6000 tokens (~24K chars, ~4 chars/token)
        // 1. Module context: имя, тип, dependencies, extension points (~500 tokens)
        // 2. Evidence: languages, architecture, patterns (~500 tokens)
        // 3. Attached files: variable
        // 4. Code chunks: fill remaining budget (более релевантные = больше токенов)
    }
}
```

#### 2.13 IntentResolver.kt
```kotlin
class IntentResolver(ollamaClient, config) {
    suspend fun resolve(userInput: String): ResolvedIntent {
        // Отправить userInput в classify-модель (qwen3:1.7b)
        // Промпт: "Classify task: {input}. Return JSON: {task_type, confidence}"
        // Парсинг JSON → ResolvedIntent(taskId, taskType, confidence)
    }
}
```

#### 2.14 TaskType.kt
20 типов задач:
```kotlin
enum class TaskType(displayName, generatesCode, requiresDeepAnalysis, modelRole) {
    REPO_ANALYSIS("Repository Analysis", requiresDeepAnalysis=true),
    PROJECT_OVERVIEW("Project Overview"),
    ARCHITECTURE_REVIEW("Architecture Review", requiresDeepAnalysis=true),
    SECURITY_REVIEW("Security Review", requiresDeepAnalysis=true),
    BUG_ANALYSIS("Bug Analysis", requiresDeepAnalysis=true),
    IMPLEMENT_FEATURE("Implement Feature", generatesCode=true, requiresDeepAnalysis=true, CODE),
    CODE_QUALITY_REVIEW("Code Quality", requiresDeepAnalysis=true),
    PLUGIN_DEVELOPMENT("Plugin Development", generatesCode=true, requiresDeepAnalysis=true, CODE),
    EXTENSION_POINT_IMPL("Extension Point Impl", generatesCode=true, requiresDeepAnalysis=true, CODE),
    // ... и ещё ~11 типов
}
```

#### 2.15 RepoScanner.kt
```kotlin
class RepoScanner(config) {
    fun scan(repoPath: Path, db: Database): ScanResult {
        // Files.walkFileTree() с ignorePatterns (.git, node_modules, build, etc.)
        // Classify: source/test/config/build/runtime по расширению
        // Batch INSERT через db.insertFilesBatch() (500 за транзакцию)
    }
}
```

#### 2.16 Chunker.kt
```kotlin
class Chunker(config) {
    fun chunkAll(db: Database): Int {
        // Для каждого файла в БД:
        //   Читать content, разбить на чанки по 80 строк с overlap 10
        //   Определить symbol_name (имя функции/класса в чанке)
        //   db.insertChunksBatch()
    }
}
```

#### 2.17 PromptBuilder.kt + промпт-шаблоны
Создай файлы ресурсов:
- `prompts/system.txt` — базовый системный промпт
- `prompts/intent_classification.txt` — промпт для классификации
- `prompts/repo_analysis.txt` — для анализа репо
- `prompts/implement_feature.txt` — для имплементации
- и т.д.

```kotlin
class PromptBuilder(config) {
    fun buildTaskPrompt(taskType, userInput, context, evidence, moduleContext): List<Message>
    fun buildIntelliJTaskPrompt(...): List<Message>  // с IntelliJ Platform knowledge
}
```

#### 2.18 ModelSelector.kt
```kotlin
class ModelSelector(config, ollamaClient) {
    fun selectForTask(taskType: TaskType): String {
        // ModelRole.CODE → config.models.code (qwen2.5-coder:14b)
        // ModelRole.REASON → config.models.reason (deepseek-r1:8b)
        // ModelRole.CLASSIFY → config.models.classify (qwen3:1.7b)
        // Проверить доступность, fallback на альтернативу
    }
}
```

#### 2.19 UI: ForgeConsole.kt + ReplShell.kt
```kotlin
class ForgeConsole(showTrace: Boolean) {
    fun banner()           // ASCII art "FORGE"
    fun info(msg)          // зелёный текст
    fun error(msg)         // красный
    fun warn(msg)          // жёлтый
    fun success(msg)       // bold зелёный
    fun result(msg)        // основной вывод с markdown
    fun printTrace(trace)  // таблица: Stage | Detail | Duration
}

class ReplShell(services, repoPath) {
    // Интерактивный REPL с командами:
    // /focus <module> — установить фокус
    // /modules — список модулей
    // /repos — список репозиториев
    // /help — справка
    // /clear — очистить историю
    // /quit — выход
    // Всё остальное → orchestrator.execute()
}
```

#### 2.20 WorkspaceManager.kt + MultiRepoManager.kt
```kotlin
class WorkspaceManager(config) {
    fun getOrCreate(repoPath: Path): Workspace {
        // SHA256(repoPath) → workspace ID
        // ~/.forge/workspaces/{id}/workspace.db
        // Return Workspace(path, db)
    }
}

class MultiRepoManager(config, workspaceManager) {
    fun connectRepo(workspace, name, url): RepoInfo
    fun listRepos(workspace): List<RepoInfo>
}
```

#### 2.21 Файловые экстракторы (files/)
- `FileProcessor.kt` — dispatcher по типу файла
- `PdfExtractor.kt` — через PDFBox
- `DocxExtractor.kt` — через Apache POI
- `XlsxExtractor.kt` — через Apache POI
- `CsvExtractor.kt` — парсинг CSV
- `TextExtractor.kt` — plain text
- `ImageProcessor.kt` — описание через Ollama vision model

#### 2.22 Голосовой ввод (voice/)
- `VoiceInput.kt` — запись через javax.sound.sampled
- `WhisperTranscriber.kt` — транскрипция через whisper-jni
- `VoiceConfig.kt` — настройки (язык, silence threshold)

## Структура файлов
```
forge/
├── build.gradle.kts
├── settings.gradle.kts            # rootProject.name = "forge"
├── gradle.properties              # kotlin.code.style=official
├── src/main/kotlin/forge/
│   ├── Config.kt
│   ├── Main.kt
│   ├── core/
│   │   ├── IntentResolver.kt
│   │   ├── Orchestrator.kt
│   │   ├── Pipeline.kt
│   │   ├── StateManager.kt
│   │   └── TaskType.kt
│   ├── intellij/
│   │   ├── IntelliJModuleResolver.kt
│   │   ├── IntelliJPatterns.kt
│   │   └── PluginXmlParser.kt
│   ├── llm/
│   │   ├── ContextAssembler.kt
│   │   ├── ModelSelector.kt
│   │   ├── OllamaClient.kt
│   │   ├── PromptBuilder.kt
│   │   └── ResponseParser.kt
│   ├── retrieval/
│   │   ├── Chunker.kt
│   │   ├── DependencyMapper.kt
│   │   ├── EvidenceCollector.kt
│   │   ├── HierarchicalRetriever.kt
│   │   └── RepoScanner.kt
│   ├── workspace/
│   │   ├── Database.kt
│   │   ├── EmbeddingStore.kt
│   │   ├── MultiRepoManager.kt
│   │   └── WorkspaceManager.kt
│   ├── ui/
│   │   ├── ForgeConsole.kt
│   │   ├── ReplShell.kt
│   │   └── TraceDisplay.kt
│   ├── files/
│   │   ├── FileProcessor.kt
│   │   ├── PdfExtractor.kt
│   │   ├── DocxExtractor.kt
│   │   ├── XlsxExtractor.kt
│   │   ├── CsvExtractor.kt
│   │   ├── TextExtractor.kt
│   │   └── ImageProcessor.kt
│   ├── voice/
│   │   ├── VoiceInput.kt
│   │   ├── WhisperTranscriber.kt
│   │   └── VoiceConfig.kt
│   └── tasks/
│       ├── TaskHandler.kt
│       └── Handlers.kt
├── src/main/resources/
│   ├── forge-default.yaml
│   └── prompts/
│       ├── system.txt
│       ├── intent_classification.txt
│       ├── repo_analysis.txt
│       ├── architecture_review.txt
│       ├── bug_analysis.txt
│       ├── code_quality.txt
│       ├── implement_feature.txt
│       ├── security_review.txt
│       └── summarize.txt
└── Makefile
```

## Критические детали реализации (обязательно учти!)

### 1. Embedding lifecycle
- nomic-embed-text имеет BERT context = 2048 tokens
- Код tokenизируется примерно 1-3 char/token
- ОБЯЗАТЕЛЬНО: `maxEmbedChars = 1800` — обрезать content перед embedding
- При ошибке embedding (HTTP 500) → записать `ByteArray(0)` как маркер failed
- В поиске пропускать chunks с `embedding.isEmpty()` (failed markers)
- Embedding хранится как BLOB: `FloatArray → ByteArray` (768 × 4 = 3072 bytes)

### 2. SQLite производительность
- `PRAGMA journal_mode=WAL` — в init И в getConnection()
- `PRAGMA busy_timeout=30000` — защита от SQLITE_BUSY при параллельной записи
- Все DB-методы: `synchronized(lock) { ... }`
- Batch INSERT: `conn.autoCommit = false → executeBatch() → commit()`

### 3. Scale для 180K+ файлов
- `moduleEmbeddingBudget = 1000` — макс новых embeddings на модуль за запрос
- `moduleTopK = 5` — сколько модулей выбирать в Stage 1
- `similaritySearchLimit = 5000` — макс чанков для cosine similarity
- `tokenBudget = 6000` — бюджет токенов для контекста LLM
- В IntelliJ mode: пропускать глобальный EMBED stage → lazy per-module embedding

### 4. Module-file assignment
- `assignFilesToModules()`: SQL UPDATE с LIKE matching
- `WHERE relative_path LIKE 'module_name/%'`
- Модули сортировать по длине имени DESC (более специфичные первыми)
- После assignment: `UPDATE modules SET file_count = (SELECT COUNT(*) FROM files WHERE files.module_id = modules.id)`

### 5. Cosine similarity
```kotlin
fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
    var dot = 0f; var normA = 0f; var normB = 0f
    for (i in a.indices) { dot += a[i] * b[i]; normA += a[i] * a[i]; normB += b[i] * b[i] }
    return if (normA == 0f || normB == 0f) 0f else dot / (sqrt(normA) * sqrt(normB))
}
```

### 6. ByteArray ↔ FloatArray conversion
```kotlin
fun FloatArray.toBytes(): ByteArray {
    val buf = java.nio.ByteBuffer.allocate(size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    forEach { buf.putFloat(it) }
    return buf.array()
}

fun ByteArray.toFloatArray(): FloatArray {
    val buf = java.nio.ByteBuffer.wrap(this).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    return FloatArray(size / 4) { buf.getFloat() }
}
```

## Верификация после создания
```bash
./gradlew clean build installDist

# Проверь на любом репозитории:
./build/install/forge/bin/forge status
./build/install/forge/bin/forge analyze /path/to/any/repo
./build/install/forge/bin/forge ask /path/to/repo "How does this project work?"
./build/install/forge/bin/forge shell /path/to/repo

# Для IntelliJ-community (если есть):
./build/install/forge/bin/forge modules /path/to/intellij-community
./build/install/forge/bin/forge focus platform/core-api /path/to/intellij-community "What extension points?"
```

Создай все файлы, убедись что проект компилируется и работает. Начни с build.gradle.kts и Config.kt, затем Database.kt, OllamaClient.kt, и далее по порядку. Каждый файл должен быть полностью рабочим.
```
