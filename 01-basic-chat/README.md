# Этап 1 — Базовое общение с LLM (REST)

## Чему учит этап

- Поднять Spring Boot веб-сервис, отдающий REST для общения с LLM
- Понять три фундаментальных способа запроса: simple, system+user, streaming
- Прочитать метаданные ответа: `tokenUsage`, `finishReason`
- Закрепить две главные конвенции проекта:
  1. **REST**, не CommandLineRunner
  2. **Ручная `@Configuration`**, без auto-config; `@SpringBootApplication`-класс не трогаем

## Структура модуля

```
basic/
├── BasicChatApplication.java   — только main(), без бинов
├── config/
│   └── LlmConfig.java          — @Configuration, ручные @Bean методы
└── web/
    ├── ChatController.java     — три REST-эндпоинта
    ├── SimpleChatRequest.java  — DTO для /api/chat
    ├── ChatRequest.java        — DTO для /api/chat/with-system и /api/chat/stream
    └── ChatResponse.java       — DTO синхронных ответов
```

Все Java-файлы написаны в стиле «проект-конспект»: подробные Javadoc-комментарии,
объясняющие концепции LangChain4j, конвенции проекта и связи между этапами.

## Эндпоинты

Сервис поднимается на `http://localhost:8081`. Все запросы — `POST`, тело — JSON.

### 1. `POST /api/chat` — простой чат

Один пользовательский вопрос → один ответ. Без системного промпта.

```bash
curl -s -X POST http://localhost:8081/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"Что такое ИИ-агент?"}' | jq .
```

Ответ:
```json
{
  "reply": "ИИ-агент — это …",
  "inputTokens": 12,
  "outputTokens": 87,
  "finishReason": "STOP"
}
```

### 2. `POST /api/chat/with-system` — чат с системным промптом

Системный промпт задаёт «характер» / роль модели.

```bash
curl -s -X POST http://localhost:8081/api/chat/with-system \
  -H 'Content-Type: application/json' \
  -d '{
    "system":"Ты строгий технический рецензент. Отвечай по делу.",
    "user":"В одном абзаце: чем ИИ-агент отличается от обычного чат-бота на LLM?"
  }' | jq .
```

### 3. `POST /api/chat/stream` — стриминг через SSE

Ответ приходит частями по мере генерации.

```bash
curl -N -X POST http://localhost:8081/api/chat/stream \
  -H 'Content-Type: application/json' \
  -d '{
    "system":"Отвечай кратко.",
    "user":"Перечисли 5 ключевых компонентов ИИ-агента."
  }'
```

Поток событий:
```
data: ИИ
data: -агент
data:  состоит
...
event: done
data: input=18, output=92
```

## Запуск

> **Важно:** для работы нужен реальный LLM-провайдер. По умолчанию модуль настроен на
> локальную Ollama (`qwen2.5:7b` через `localhost:11434`). Если Ollama не доступна,
> в `LlmConfig.java` достаточно заменить `OllamaChatModel.builder()` на
> `AnthropicChatModel.builder()` и пробросить `ANTHROPIC_API_KEY` — интерфейсы
> `ChatModel` / `StreamingChatModel` остаются те же, контроллер не меняется.

```bash
# из корня репозитория
mvn -pl common-llm,01-basic-chat -am install -DskipTests
mvn -pl 01-basic-chat spring-boot:run
```

## Конфигурация (application.yml)

| Свойство | Дефолт | Назначение |
|----------|--------|-----------|
| `server.port` | `8081` | HTTP-порт |
| `ollama.base-url` | `http://localhost:11434` | URL Ollama-демона |
| `ollama.model` | `qwen2.5:7b` | имя модели |
| `ollama.temperature` | `0.7` | креативность 0.0–2.0 |

## Что попробовать самому

1. Поменять `ollama.temperature` (0.0 vs 1.5) — увидеть разницу в ответах.
2. В `LlmConfig` добавить `.numPredict(50)` к билдеру — поймать `finishReason: "LENGTH"`.
3. Поменять модель на `qwen2.5:3b` (если 7b тяжело) — сравнить качество и скорость.
4. Подменить `OllamaChatModel` на `AnthropicChatModel` — увидеть, что контроллер не меняется.
