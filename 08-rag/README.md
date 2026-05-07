# Этап 8 — RAG (Retrieval-Augmented Generation)

## Цели

- Дать агенту знания, которых нет в LLM (свои документы, БЗ, тикеты)
- Построить пайплайн: документы → чанки → embeddings → векторная БД → поиск
- Подключить `ContentRetriever` к `AiServices`
- Освоить улучшения: гибридный поиск (BM25 + vectors), ре-ранжирование, query expansion

## Ключевые концепции

| Концепция | Назначение |
|-----------|-----------|
| `Document` | исходный документ |
| `DocumentLoader` | загрузка из ФС/URL/S3 |
| `DocumentSplitter` | нарезка на чанки |
| `EmbeddingModel` | текст → вектор |
| `EmbeddingStore` | хранилище векторов |
| `ContentRetriever` | поиск релевантных кусков |
| `RetrievalAugmentor` | оркестратор RAG-пайплайна |

## Что строим

1. **Easy RAG**: `EasyRagBuilder` за 10 строк — папка с PDF/MD → агент отвечает по ним
2. **Свой пайплайн**: загрузчик → splitter → embedder → in-memory store
3. **pgvector**: переезд на Postgres+pgvector
4. **Гибридный поиск**: BM25 + векторы, ре-ранжирование
5. **Query routing**: классификация запроса → выбор retriever

## Подготовка

```bash
# Postgres с pgvector через Docker
docker run -d --name pgvector \
  -e POSTGRES_USER=rag -e POSTGRES_PASSWORD=rag -e POSTGRES_DB=rag \
  -p 5432:5432 pgvector/pgvector:pg16

# Положить тестовые документы
mkdir -p docs && echo "Sample doc" > docs/sample.md
```
