package com.study.aiagents.rag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class RagApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagApplication.class, args);
    }

    // TODO: реализовать на этапе 8
    //  - DocumentLoader (FileSystemDocumentLoader, UrlDocumentLoader)
    //  - DocumentSplitter (recursive, by paragraph, semantic)
    //  - EmbeddingModel (локальный bge-small-en-v15 или Voyage через Anthropic)
    //  - EmbeddingStore: InMemoryEmbeddingStore → pgvector
    //  - EmbeddingStoreContentRetriever
    //  - RetrievalAugmentor с гибридным поиском (BM25 + vectors) и реранкингом
    //  - Подключение в AiServices через .contentRetriever(...)
}
