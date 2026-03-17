# FORGE v2 — Local AI Code Intelligence Agent

**FORGE** (Fast Offline Repository Graph Engine) — CLI-агент для анализа масштабных кодовых баз с использованием локальных LLM через Ollama. Специально спроектирован для работы с intellij-community (180K+ файлов, 598K чанков, 1438 модулей).

## Быстрый старт

### Требования
- **JDK 21+** (протестировано на Temurin 25)
- **Ollama** запущен локально (`http://127.0.0.1:11434`)
- Модели Ollama: `qwen2.5-coder:14b`, `nomic-embed-text`, `qwen3:1.7b`

### Установка

```bash
git clone https://github.com/NikolaSalta/forge.git
cd forge
./gradlew clean build installDist
```

### Использование

```bash
# Полный анализ репозитория
./build/install/forge/bin/forge analyze /path/to/repo

# Фокус на конкретном IntelliJ модуле
./build/install/forge/bin/forge focus platform/core-api /path/to/intellij-community "What extension points does this module define?"

# Список всех IntelliJ модулей
./build/install/forge/bin/forge modules /path/to/intellij-community

# Фильтрация модулей по типу
./build/install/forge/bin/forge modules /path/to/intellij-community --type PLATFORM_API

# Вопрос к репозиторию
./build/install/forge/bin/forge ask /path/to/repo "How does authentication work?"

# Интерактивный REPL
./build/install/forge/bin/forge shell /path/to/repo

# Подключить сателлитный репозиторий
./build/install/forge/bin/forge connect JetBrains/kotlin /path/to/intellij-community

# Статус системы
./build/install/forge/bin/forge status
```

## Ключевые возможности

### Иерархический двухстадийный поиск
Для репозиториев с 600K+ чанков глобальный embedding-поиск невозможен. FORGE использует двухстадийный подход:
1. **Stage 1 (Module Search):** Keyword-matching по имени модуля, зависимостям, summary → top-5 модулей
2. **Stage 2 (Chunk Search):** Lazy embedding + cosine similarity только внутри выбранных модулей → top-20 чанков

### Ленивое эмбеддирование (Lazy Embedding)
Эмбеддинги генерируются **по запросу** — только для модулей, к которым обращается пользователь. Это позволяет работать с 600K чанков без предварительной обработки всей базы.

### IntelliJ Module Discovery
Автоматически обнаруживает 1438 модулей через парсинг:
- `plugin.xml` (174 файла) — extension points, services, depends
- `.iml` файлы (2577 штук) — зависимости между модулями
- Классификация: PLATFORM_API (309), PLUGIN (785), LIBRARY (145), COMMUNITY (97), PLATFORM_IMPL (60), TEST (42)

### Multi-repo поддержка
Подключение сателлитных репозиториев JetBrains (kotlin, android) для unified search.

### Pipeline архитектура
Каждый запрос проходит через настраиваемый pipeline:
```
INTENT → WORKSPACE → SCAN → MODULE_DISCOVERY → CHUNK → EMBED → EVIDENCE → CONTEXT_ASSEMBLY → LLM_CALL → VALIDATE
```

## Конфигурация

Файл `forge-default.yaml` содержит все настройки. Создайте `forge.yaml` рядом с binary для override:

```yaml
ollama:
  host: "http://127.0.0.1:11434"

models:
  code: "qwen2.5-coder:14b"
  embed: "nomic-embed-text"
  classify: "qwen3:1.7b"

intellij:
  enabled: true  # включить IntelliJ Platform mode

scale:
  module_embedding_budget: 1000  # макс чанков для embedding на модуль
  module_top_k: 5               # сколько модулей искать
  similarity_search_limit: 5000  # макс чанков для cosine similarity
```

## Технический стек

| Компонент | Технология |
|-----------|------------|
| Язык | Kotlin 2.3.0 |
| Runtime | JVM 21 (Java 25 Temurin) |
| Сборка | Gradle Kotlin DSL |
| LLM | Ollama API (local inference) |
| Embeddings | nomic-embed-text (BERT, 768-dim) |
| Code LLM | qwen2.5-coder:14b |
| База данных | SQLite WAL + FTS5 |
| CLI | Clikt (Kotlin CLI framework) |
| Terminal | Mordant (rich terminal output) |

## Статистика на intellij-community

| Метрика | Значение |
|---------|----------|
| Файлов просканировано | 180,505 |
| Чанков создано | 597,984 |
| Модулей обнаружено | 1,438 |
| Файлов привязано к модулям | 180,283 (99.88%) |
| Время `forge focus` | ~3 мин |
| Размер workspace DB | ~2 GB |

## Структура проекта

```
src/main/kotlin/forge/
├── Config.kt                          # Конфигурация (YAML parsing)
├── Main.kt                            # CLI entry point (Clikt commands)
├── core/
│   ├── IntentResolver.kt              # Классификация задач через LLM
│   ├── Orchestrator.kt                # Главный оркестратор выполнения
│   ├── Pipeline.kt                    # Pipeline builder + stages
│   ├── StateManager.kt                # Pause/Stop state
│   └── TaskType.kt                    # 20 типов задач
├── intellij/
│   ├── IntelliJModuleResolver.kt      # Обнаружение модулей
│   ├── IntelliJPatterns.kt            # Regex для IntelliJ API
│   └── PluginXmlParser.kt            # XML парсер plugin.xml
├── llm/
│   ├── ContextAssembler.kt            # Token budget management
│   ├── ModelSelector.kt               # Выбор модели по задаче
│   ├── OllamaClient.kt               # HTTP клиент к Ollama
│   ├── PromptBuilder.kt              # Сборка промптов
│   └── ResponseParser.kt             # Парсинг ответов LLM
├── retrieval/
│   ├── Chunker.kt                     # Семантическая разбивка файлов
│   ├── DependencyMapper.kt            # Граф зависимостей
│   ├── EvidenceCollector.kt           # 15+ детекторов паттернов
│   ├── HierarchicalRetriever.kt       # Двухстадийный поиск
│   └── RepoScanner.kt                # Параллельное сканирование
├── workspace/
│   ├── Database.kt                    # SQLite (8 таблиц, batch ops)
│   ├── EmbeddingStore.kt             # Embedding management
│   ├── MultiRepoManager.kt           # Мульти-репо оркестрация
│   └── WorkspaceManager.kt           # Управление workspace
├── ui/
│   ├── ForgeConsole.kt               # Rich terminal output
│   ├── ReplShell.kt                  # Интерактивный REPL
│   └── TraceDisplay.kt              # Trace визуализация
├── files/                             # Экстракторы (PDF, DOCX, XLSX)
└── voice/                             # Whisper JNI voice input
```

## Лицензия

MIT
