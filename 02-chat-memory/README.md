# Этап 2 — Память диалога (REST + ChatMemory)

## Чему учит этап

- Сделать беседу с моделью **stateful между HTTP-запросами**
- Понять три ключевые абстракции LangChain4j: `ChatMemoryStore`, `ChatMemory`, `ChatMemoryProvider`
- Написать собственный `ChatMemoryStore` (in-memory) и увидеть его контракт
- Изолировать память по `userId` (мульти-пользовательский режим)
- Корректно прикрепить системный промпт ровно один раз в начало беседы

## Структура модуля

```
memory/
├── ChatMemoryApplication.java         — только main()
├── config/
│   ├── LlmConfig.java                 — @Bean ChatModel (синхронная модель Ollama)
│   └── MemoryConfig.java              — @Bean ChatMemoryStore + ChatMemoryProvider
├── store/
│   └── InMemoryChatMemoryStore.java   — учебная реализация ChatMemoryStore
├── service/
│   └── ChatService.java               — оркестратор: память + модель
└── web/
    ├── ChatController.java            — три REST-эндпоинта
    ├── ChatRequest.java               — { message }
    ├── ChatResponse.java              — { reply, inputTokens, outputTokens, finishReason }
    ├── HistoryResponse.java           — { messages: [...] }
    └── MessageDto.java                — { role, text }
```

## Эндпоинты

Сервис на `http://localhost:8082`. Идентификатор беседы (`userId`) — в пути.

### 1. `POST /api/chat/{userId}` — продолжить беседу

```bash
# Первое сообщение — заводим беседу пользователя alice
curl -X POST http://localhost:8082/api/chat/alice \
  -H 'Content-Type: application/json' \
  -d '{"message":"Меня зовут Алиса, запомни."}'

# Второе сообщение в той же беседе — ожидаем, что модель помнит имя
curl -X POST http://localhost:8082/api/chat/alice \
  -H 'Content-Type: application/json' \
  -d '{"message":"Как меня зовут?"}'
```

Ответ:
```json
{
  "reply": "Тебя зовут Алиса.",
  "inputTokens": 56,
  "outputTokens": 8,
  "finishReason": "STOP"
}
```

> Заметь, как `inputTokens` растёт от запроса к запросу — в каждый новый запрос
> упаковывается вся накопленная история. Это и есть источник «беседа дорожает
> по мере общения».

### 2. `GET /api/chat/{userId}/history` — посмотреть историю

```bash
curl http://localhost:8082/api/chat/alice/history | jq .
```

```json
{
  "messages": [
    { "role": "system", "text": "Ты — помощник, который …" },
    { "role": "user", "text": "Меня зовут Алиса, запомни." },
    { "role": "ai",   "text": "Хорошо, запомнил, Алиса." },
    { "role": "user", "text": "Как меня зовут?" },
    { "role": "ai",   "text": "Тебя зовут Алиса." }
  ]
}
```

### 3. `DELETE /api/chat/{userId}` — забыть беседу

```bash
curl -X DELETE http://localhost:8082/api/chat/alice -i
# HTTP/1.1 204 No Content
```

После этого `GET .../history` вернёт пустой список — память чистая.

## Конфигурация

| Свойство | Дефолт | Назначение |
|----------|--------|-----------|
| `server.port` | `8082` | HTTP-порт |
| `ollama.base-url` | `http://localhost:11434` | URL Ollama-демона |
| `ollama.model` | `qwen2.5:7b` | имя модели |
| `chat.system-prompt` | (есть осмысленный дефолт) | один раз кладётся в начало беседы |
| `chat.memory.max-messages` | `20` | размер окна `MessageWindowChatMemory` |

## Важные концепции

### Три абстракции памяти LangChain4j

| Абстракция | Роль | Кто реализует |
|-----------|------|---------------|
| `ChatMemoryStore` | persistence: read/write/delete | мы (`InMemoryChatMemoryStore`) → в проде Redis/Postgres |
| `ChatMemory` | политика обрезки (window/token) | LangChain4j (`MessageWindowChatMemory`) |
| `ChatMemoryProvider` | фабрика памяти на каждый `memoryId` | мы (лямбда в `MemoryConfig`) |

### MessageWindowChatMemory vs TokenWindowChatMemory

- **MessageWindowChatMemory** — «храни последние N сообщений». Прост и предсказуем. Используем здесь.
- **TokenWindowChatMemory** — «храни сообщения, пока влезают в M токенов». Нужен токенайзер под конкретную модель. Точнее контролирует стоимость, но требует подключения `Tokenizer`.

Альтернатива `TokenWindowChatMemory` показана как комментарий в `MemoryConfig.java` —
включить можно за пару строк, когда захочется управлять бюджетом по токенам.

### Почему системный промпт добавляется один раз

В `ChatService.ask()` системный промпт кладётся в память **только если она пустая**.
Если бы добавляли на каждый запрос — история быстро забилась бы дублями системника,
плюс мы бы платили за лишние токены. Один раз в начало беседы — стандартный паттерн.

## Запуск

> **Важно:** для работы нужен живой LLM-провайдер. По умолчанию модуль настроен на Ollama
> (`qwen2.5:7b`). Если Ollama не доступна — в `LlmConfig.java` поменять `OllamaChatModel.builder()`
> на `AnthropicChatModel.builder()` (см. README этапа 1).

```bash
# из корня репозитория
mvn -pl common-llm,02-chat-memory -am install -DskipTests
mvn -pl 02-chat-memory spring-boot:run
```

## Что попробовать самому

1. **Проверить изоляцию.** Завести двух пользователей (`alice` / `bob`), сообщить разные имена,
   через `/history` убедиться, что истории не пересекаются.
2. **Уменьшить окно.** Поставить `chat.memory.max-messages: 4` — после нескольких реплик
   модель начнёт «забывать» начало диалога.
3. **Подменить store.** Написать `FileChatMemoryStore`, сохраняющий историю на диск, —
   увидеть, что после рестарта приложения беседы остались.
4. **Добавить стриминг.** Скопировать паттерн `chatStream` из этапа 1 — он работает
   поверх памяти один в один (только заменить `chatModel` на `streamingChatModel`).
