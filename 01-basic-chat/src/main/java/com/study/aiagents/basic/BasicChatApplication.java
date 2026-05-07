package com.study.aiagents.basic;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Этап 1 — точка входа модуля.
 * <p>
 * Этот класс намеренно пустой и содержит только {@code main()}.
 * Конвенция проекта (см. README): всё, что связано с настройкой бинов
 * (модели, retriever-ы, memory и т.д.) — вынесено в отдельные
 * {@code @Configuration}-классы. Это правило соблюдается во всех 12 этапах:
 * мы хотим видеть каждое создание бина явно, без авто-конфигурации.
 * <p>
 * Связанные классы:
 * <ul>
 *     <li>{@code config.LlmConfig}        — создаёт {@code ChatModel} и {@code StreamingChatModel}</li>
 *     <li>{@code web.ChatController}      — REST-эндпоинты, через которые мы общаемся с моделью</li>
 * </ul>
 */
@SpringBootApplication
public class BasicChatApplication {

    public static void main(String[] args) {
        SpringApplication.run(BasicChatApplication.class, args);
    }
}
