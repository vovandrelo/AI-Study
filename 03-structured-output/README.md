# Этап 3 — Структурированный вывод

## Цели

- Заставить LLM возвращать строго типизированные данные (POJO/record)
- Применять `PromptTemplate` с переменными
- Валидировать результат через `jakarta.validation`
- Обрабатывать невалидный ответ (retry, fallback)

## Ключевые концепции

| Концепция | Назначение |
|-----------|-----------|
| `PromptTemplate` | шаблоны с подстановкой `{{var}}` |
| `@StructuredPrompt` | декларативный шаблон на классе |
| `AiServices` + record | возврат типизированного результата |
| Few-shot examples | примеры в системном промпте |

## Что строим

1. Извлечение сущностей из текста: `record Person(String name, int age, List<String> skills)`
2. Few-shot классификатор тональности: `enum Sentiment { POSITIVE, NEGATIVE, NEUTRAL }`
3. Парсер заявок в JIRA-подобный формат с валидацией
4. Retry-обёртка на невалидный JSON
