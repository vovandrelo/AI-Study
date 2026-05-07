package com.study.aiagents.memory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Этап 2 — точка входа модуля.
 * <p>
 * Тема этапа: <b>память диалога между HTTP-запросами</b>. Простой LLM-вызов
 * stateless: модель не помнит, что было в предыдущем запросе. Чтобы беседа
 * выглядела как беседа, сервер должен:
 * <ol>
 *     <li>хранить историю сообщений на стороне сервера;</li>
 *     <li>при каждом новом запросе подмешивать историю к новому user-сообщению;</li>
 *     <li>обрезать историю до разумного размера (окно сообщений или окно токенов);</li>
 *     <li>изолировать историю по идентификатору пользователя/сессии.</li>
 * </ol>
 * <p>
 * Этот класс намеренно пустой. Бины разнесены по {@code config.LlmConfig}
 * и {@code config.MemoryConfig}, оркестрация — в {@code service.ChatService},
 * REST-контракт — в {@code web.ChatController}.
 */
@SpringBootApplication
public class ChatMemoryApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChatMemoryApplication.class, args);
    }
}
