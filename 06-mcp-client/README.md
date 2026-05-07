# Этап 6 — MCP-клиент

## Цели

- Подключить готовые MCP-серверы как `ToolProvider` в LangChain4j
- Освоить оба транспорта: `stdio` (запуск процессом) и `Streamable HTTP` (удалённый)
- Управлять набором инструментов (whitelist/blacklist), таймаутами

## Что такое MCP

**Model Context Protocol** — открытый протокол Anthropic, по которому LLM-клиент общается с серверами, отдающими **tools / resources / prompts**. Готовых серверов сотни (filesystem, GitHub, Slack, Postgres, Playwright и т.д.) — ты подключаешь сервер, и Claude автоматически получает его инструменты.

## Ключевые концепции

| Концепция | Назначение |
|-----------|-----------|
| `McpClient` | LangChain4j-клиент к MCP-серверу |
| `StdioMcpTransport` | запуск сервера как процесса |
| `StreamableHttpMcpTransport` | подключение к удалённому HTTP-серверу |
| `McpToolProvider` | мост MCP → tools для `AiServices` |

## Что строим

1. Подключаемся к `@modelcontextprotocol/server-filesystem` через stdio — агент читает/пишет файлы во временной директории.
2. Подключаемся к `server-fetch` — агент скачивает страницы из интернета.
3. Подключаемся к `server-github` (нужен токен) — агент ищет issues и PR.
4. Несколько серверов одновременно → один агент.

## Подготовка

```bash
# Node.js нужен для npx-серверов
brew install node
# Проверка одного из серверов
npx -y @modelcontextprotocol/server-filesystem /tmp
```
