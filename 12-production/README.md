# Этап 12 — Production-ready агенты

## Цели

- Сделать агентов наблюдаемыми, надёжными и тестируемыми
- Защитить агента от вредных входов и собственных галлюцинаций
- Контролировать стоимость и качество в продакшене

## Темы

### Observability
- **OpenTelemetry**: трассировка цепочки `request → agent → tool → LLM` со span-ами
- **Метрики через Micrometer**: токены/запрос, латентность, RPS, error rate
- **Логи**: структурированные (JSON), с `traceId` в каждой строке
- Дашборды: Grafana + Tempo + Prometheus (через docker-compose)

### Reliability
- **Retry**: Resilience4j на LLM-вызовы (timeout, экспоненциальный back-off)
- **Circuit breaker**: при деградации Anthropic — fallback на Ollama
- **Idempotency**: ключи запросов, чтобы не платить дважды

### Caching
- **Кэш ответов**: Caffeine/Redis (одинаковые промпты → один результат)
- **Anthropic prompt caching**: пометить системный промпт как кэшируемый — снижение цены до 10×

### Guardrails
- **Input**: PII-фильтр (regex + классификатор), детектор jailbreak
- **Output**: модерация, проверка структуры, отказ от запрещённых тем
- **Rate limits**: per-user, per-IP, per-tenant

### Testing
- Unit-тесты с моком `ChatLanguageModel`
- Контракт-тесты для `@Tool`-методов
- **Eval-датасет**: набор `(input, expected_behavior)`, прогон при каждом изменении промпта
- Метрики качества: faithfulness, relevance, answer correctness

## Инфраструктура

```bash
# OTel collector + Tempo + Prometheus + Grafana
docker compose -f docker/observability.yml up -d
```

(docker-compose файл создаём на этом этапе)
