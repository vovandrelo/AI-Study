# Этап 4 — AI Services

## Цели

- Превратить интерфейс Java в декларативный LLM-агент через `AiServices`
- Использовать аннотации `@SystemMessage`, `@UserMessage`, `@V`
- Подключить агента в Spring как обычный бин через `langchain4j-spring-boot-starter`

## Ключевые концепции

| Концепция | Назначение |
|-----------|-----------|
| `AiServices` | фабрика агентов из интерфейсов |
| `@SystemMessage` | системный промпт на методе/классе |
| `@UserMessage` | шаблон пользовательского сообщения |
| `@V` | подстановка параметров метода в шаблон |
| `TokenStream` | стриминг как возвращаемый тип |

## Что строим

```java
interface Translator {
    @SystemMessage("Ты профессиональный переводчик. Переводи без комментариев.")
    @UserMessage("Переведи на {{lang}}: {{text}}")
    String translate(@V("lang") String lang, @V("text") String text);
}
```

И тот же интерфейс с памятью + RAG, подключённый одним бином в Spring-контексте.
