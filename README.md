# AI Agents Study

Поэтапное изучение создания ИИ-агентов на Java.

## Стек

- **Java 21** (records, pattern matching, virtual threads)
- **Spring Boot 3.4**
- **Maven** (multi-module)
- **LangChain4j** — основная библиотека для работы с LLM
- **LangGraph4j** — оркестрация мультиагентных графов
- **Claude** (Anthropic) — основная LLM (`claude-sonnet-4-6`)
- **Ollama** — fallback для локальной разработки без API-ключа

## Структура

| Модуль | Тема |
|--------|------|
| `common-llm` | Общая конфигурация LLM-провайдера (Claude / Ollama через профили Spring) |
| `01-basic-chat` | Простые запросы к LLM, стриминг, токены |
| `02-chat-memory` | Память диалога, persistence истории |
| `03-structured-output` | Возврат POJO из LLM, валидация |
| `04-ai-services` | Декларативные агенты через `AiServices` |
| `05-tools` | Function calling — собственные `@Tool`-методы |
| `06-mcp-client` | Подключение готовых MCP-серверов (filesystem, GitHub, …) |
| `07-mcp-server` | Свой MCP-сервер на Java SDK |
| `08-rag` | Retrieval-Augmented Generation, embeddings, векторные БД |
| `09-agent-patterns` | ReAct, Plan-and-Execute, Reflection |
| `10-langgraph-basics` | StateGraph, узлы, рёбра, чекпоинты, human-in-the-loop |
| `11-multi-agent` | Supervisor, pipeline, debate |
| `12-production` | Observability, guardrails, кэш, тесты, метрики качества |

## Быстрый старт

```bash
# 1. Скопировать env-шаблон
cp .env.example .env

# 2. Поднять Ollama (на время отсутствия ключа Claude)
brew install ollama
ollama serve &
ollama pull qwen2.5:7b

# 3. Собрать всё
mvn clean install -DskipTests

# 4. Запустить первый этап
mvn -pl 01-basic-chat spring-boot:run
```

## Переключение провайдера LLM

В `application.yml` каждого модуля используется бин из `common-llm`. Профиль выбирается переменной `LLM_PROVIDER`:

- `LLM_PROVIDER=ollama` — локальная модель (по умолчанию, без ключей)
- `LLM_PROVIDER=anthropic` — Claude (когда появится `ANTHROPIC_API_KEY`)

Код агента остаётся одинаковым — меняется только бин `ChatLanguageModel`.

## Версии библиотек

В parent-pom через свойства:
- `langchain4j.version` — `1.0.1`
- `langgraph4j.version` — `1.5.5`
- `mcp-sdk.version` — `0.10.0`

При расхождениях с актуальными релизами — поднять версию в `pom.xml`.
