package com.study.aiagents.basic.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Этап 1 — конфигурация LLM-провайдера (вручную, без auto-configuration).
 * <p>
 * <b>Ключевые концепции:</b>
 * <ul>
 *     <li>{@link ChatModel} — основной интерфейс LangChain4j для синхронного запроса к модели.
 *         Принимает список {@code ChatMessage} (system/user/ai/tool) и возвращает {@code ChatResponse}
 *         с финальным сообщением и метаданными (токены, причина завершения).</li>
 *     <li>{@link StreamingChatModel} — то же самое, но ответ приходит частями через колбэк
 *         {@code StreamingChatResponseHandler}. Используется для SSE / WebSocket / TokenStream.</li>
 * </ul>
 * <p>
 * <b>Почему вручную, а не через {@code spring-boot-starter}?</b><br>
 * В этом учебном проекте мы намеренно избегаем магии Spring Boot: каждый бин создаётся явным
 * {@code @Bean}-методом, чтобы было видно, какие параметры передаются модели и как их менять.
 * В реальном проекте можно использовать {@code langchain4j-ollama-spring-boot-starter} —
 * он сам зарегистрирует те же бины из {@code application.yml}.
 * <p>
 * <b>Переключение провайдера на Claude:</b><br>
 * Когда появится {@code ANTHROPIC_API_KEY}, заменить {@code OllamaChatModel.builder()} на
 * {@code AnthropicChatModel.builder()} (зависимость {@code langchain4j-anthropic} уже подключена
 * через модуль {@code common-llm}). Интерфейсы {@link ChatModel} / {@link StreamingChatModel}
 * при этом не изменятся — контроллер останется тот же.
 *
 * @see com.study.aiagents.basic.web.ChatController пользователь этих бинов
 */
@Configuration
public class LlmConfig {

    /**
     * Базовый URL Ollama-сервера. По умолчанию — локальный демон на стандартном порту.
     * Значение можно переопределить через {@code application.yml} или переменную окружения
     * {@code OLLAMA_BASE_URL} (Spring Boot подхватит обе формы).
     */
    @Value("${ollama.base-url:http://localhost:11434}")
    private String baseUrl;

    /**
     * Имя модели в формате {@code name:tag}. {@code qwen2.5:7b} выбран как разумный
     * компромисс качества/размера и потому, что у него хорошая поддержка tool calling
     * (понадобится начиная с Этапа 5).
     */
    @Value("${ollama.model:qwen2.5:7b}")
    private String modelName;

    /**
     * Температура: 0.0 → детерминированно, 2.0 → максимум креатива.
     * 0.7 — стандарт для общего диалога. Для задач с структурированным выводом (Этап 3)
     * обычно понижают до 0.0–0.2.
     */
    @Value("${ollama.temperature:0.7}")
    private Double temperature;

    /**
     * Синхронная модель — отдаёт финальный ответ целиком после генерации.
     * <p>
     * Параметры билдера:
     * <ul>
     *     <li>{@code .timeout(...)}      — общий таймаут HTTP-запроса. Локальные модели
     *         на CPU могут отвечать долго (особенно первый раз — модель грузится в RAM).</li>
     *     <li>{@code .logRequests/Responses(true)} — логировать тело запроса/ответа в DEBUG.
     *         Удобно для обучения, в проде обычно выключают (PII в логах).</li>
     * </ul>
     */
    @Bean
    public ChatModel chatModel() {
        return OllamaChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(modelName)
                .temperature(temperature)
                .timeout(Duration.ofMinutes(10))
                .logRequests(true)
                .logResponses(true)
                .build();
    }

    /**
     * Стриминговая модель — отдаёт ответ по частям через
     * {@code StreamingChatResponseHandler.onPartialResponse(String)}.
     * <p>
     * Технически это отдельный бин, потому что под капотом разные классы
     * ({@code OllamaChatModel} vs {@code OllamaStreamingChatModel}) и разные сетевые
     * протоколы (одиночный POST vs HTTP chunked). Бины не взаимозаменяемы — для SSE
     * нужен именно стриминговый.
     */
    @Bean
    public StreamingChatModel streamingChatModel() {
        return OllamaStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(modelName)
                .temperature(temperature)
                .timeout(Duration.ofMinutes(10))
                .build();
    }
}
