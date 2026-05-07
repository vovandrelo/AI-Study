# Этап 1 — Базовое общение с LLM (REST)

## Чему учит этап

- Поднять Spring Boot веб-сервис, отдающий REST для общения с LLM
- Понять три фундаментальных способа запроса: simple, system+user, streaming
- Прочитать метаданные ответа: `tokenUsage`, `finishReason`
- Закрепить три главные конвенции проекта:
  1. **REST**, не CommandLineRunner
  2. **Ручная `@Configuration`**, без auto-config; `@SpringBootApplication`-класс не трогаем
  3. **Системный промпт — серверная настройка**, не пользовательский ввод

## Структура модуля

```
basic/
├── BasicChatApplication.java   — только main(), без бинов
├── config/
│   └── LlmConfig.java          — @Configuration, ручные @Bean методы
└── web/
    ├── ChatController.java     — три REST-эндпоинта
    ├── ChatRequest.java        — единый DTO {message} для всех трёх эндпоинтов
    └── ChatResponse.java       — DTO синхронных ответов
```

Все Java-файлы написаны в стиле «проект-конспект»: подробные Javadoc-комментарии,
объясняющие концепции LangChain4j, конвенции проекта и связи между этапами.

## Эндпоинты

Сервис поднимается на `http://localhost:8081`. Все три эндпоинта принимают одно и то же тело:

```json
{ "message": "..." }
```

Разница — в **серверной логике**, не в API-контракте: системный промпт подмешивается
сервером (читается из `application.yml`), а не приходит от клиента.

### 1. `POST /api/chat` — без системного промпта

Один пользовательский вопрос → один ответ. Видим «сырое» поведение модели.

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

### 2. `POST /api/chat/with-system` — с серверным системным промптом

Тот же вопрос, но впереди подмешивается системный промпт из `chat.system-prompt`.
Сравни ответ с предыдущим — увидишь, как изменился стиль и тон.

```bash
curl -s -X POST http://localhost:8081/api/chat/with-system \
  -H 'Content-Type: application/json' \
  -d '{"message":"В одном абзаце: чем ИИ-агент отличается от обычного чат-бота?"}' | jq .
```

### 3. `POST /api/chat/stream` — стриминг через SSE

То же самое, что #2, но ответ приходит частями по мере генерации.

```bash
curl -N -X POST http://localhost:8081/api/chat/stream \
  -H 'Content-Type: application/json' \
  -d '{"message":"Перечисли 5 ключевых компонентов ИИ-агента."}'
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
| `chat.system-prompt` | (есть осмысленный дефолт) | системный промпт для эндпоинтов 2 и 3 |

## Что попробовать самому

1. **Сравнить эндпоинты #1 и #2** на одном и том же вопросе — увидеть, как системный промпт меняет ответ.
2. **Поменять `chat.system-prompt`** в `application.yml` (например, «отвечай только в стихах») — перезапустить сервис, попробовать снова.
3. **Поменять `ollama.temperature`** (0.0 vs 1.5) — увидеть разницу в ответах.
4. В `LlmConfig` добавить `.numPredict(50)` к билдеру — поймать `finishReason: "LENGTH"`.
5. Подменить `OllamaChatModel` на `AnthropicChatModel` — увидеть, что контроллер не меняется.
