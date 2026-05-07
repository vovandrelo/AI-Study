# Этап 10 — LangGraph4j: основы

## Цели

- Перенести агента из этапа 9 на граф состояний
- Освоить `StateGraph`, узлы, обычные и условные рёбра
- Делать чекпоинты состояния и возобновлять выполнение
- Реализовать **human-in-the-loop** через `interruptBefore`

## Ключевые концепции

| Концепция | Назначение |
|-----------|-----------|
| `StateGraph<S>` | граф с типизированным состоянием |
| `Node` | функция `S → S` (или дельта состояния) |
| `Edge` | переход между узлами |
| `ConditionalEdge` | переход по результату узла (роутинг) |
| `Checkpointer` | persistence состояния (Memory/Jdbc/Redis) |
| `interruptBefore` | пауза перед узлом для согласования с человеком |

## Что строим

1. **ReAct как граф**: `agent` ↔ `tools`, conditional edge возвращает в agent или завершает.
2. **Plan-and-Execute как граф**: `planner → executor → replan? → end`.
3. **Чекпоинты**: рестарт упавшего пайплайна с того же шага.
4. **Human-in-the-loop**: перед `tools` — пауза, ждём подтверждения в консоли.
5. **Визуализация**: экспорт в Mermaid и просмотр.

## Полезное

- Документация: https://github.com/bsorrentino/langgraph4j
- Mermaid live: https://mermaid.live/
