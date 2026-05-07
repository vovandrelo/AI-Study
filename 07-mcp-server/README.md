# Этап 7 — Собственный MCP-сервер

## Цели

- Написать свой MCP-сервер на Java SDK
- Отдать наружу **tools / resources / prompts** для нашей доменной системы
- Поднять SSE/HTTP-транспорт через `mcp-spring-webmvc`
- Протестировать сервер через MCP Inspector
- Подключить этот сервер в модуле `06-mcp-client` и через него агент Claude получит доступ к нашей системе

## Что строим

Сервер «AI-Study CRM», отдающий:

- **tools**: `findCustomer`, `createTicket`, `assignAgent`
- **resources**: `customer://{id}/profile`, `ticket://{id}`
- **prompts**: готовый шаблон `customer-summary` с подстановкой `{{customerId}}`

## Тестирование сервера

```bash
# 1. Поднять сервер
mvn -pl 07-mcp-server spring-boot:run

# 2. Открыть Inspector
npx @modelcontextprotocol/inspector
# в Inspector подключиться к http://localhost:8077/sse
```

## Подключение из агента

В модуле `06-mcp-client` добавить ещё один `StreamableHttpMcpTransport` на `http://localhost:8077` — агент получит наши доменные инструменты как обычные tools.

## Версии

`mcp-sdk.version` берётся из parent-pom. Если артефакт `io.modelcontextprotocol.sdk:mcp` не находится — посмотреть актуальный coords на https://github.com/modelcontextprotocol/java-sdk и поднять версию.
