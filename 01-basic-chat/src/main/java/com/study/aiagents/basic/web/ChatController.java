package com.study.aiagents.basic.web;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.TokenUsage;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Этап 1 — REST-контроллер с тремя эндпоинтами для общения с LLM.
 * <p>
 * <b>Зачем три эндпоинта.</b><br>
 * На этом этапе мы хотим почувствовать три фундаментальных способа взаимодействия с моделью:
 * <ol>
 *     <li>{@code POST /api/chat}             — простейший случай: один user-вопрос → один ответ.</li>
 *     <li>{@code POST /api/chat/with-system} — то же самое, но с системным промптом, задающим
 *         «характер» модели (роль, стиль, ограничения).</li>
 *     <li>{@code POST /api/chat/stream}      — стриминг ответа по мере генерации, через SSE.
 *         На длинных ответах ощутимо улучшает UX (пользователь видит первые слова сразу).</li>
 * </ol>
 * <p>
 * Все три варианта используют один и тот же набор сообщений {@code List<ChatMessage>}:
 * это базовая абстракция LangChain4j. {@link SystemMessage} и {@link UserMessage} —
 * наследники {@link ChatMessage}; в дальнейшем добавятся {@code AiMessage} (история ответов)
 * и {@code ToolExecutionResultMessage} (результаты инструментов, Этап 5).
 *
 * @see com.study.aiagents.basic.config.LlmConfig источник бинов
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatModel chatModel;
    private final StreamingChatModel streamingChatModel;

    /**
     * Конструкторная инъекция (предпочтительная в Spring): зависимости видны явно,
     * поля могут быть {@code final}, легко тестировать без Spring-контекста.
     */
    public ChatController(ChatModel chatModel, StreamingChatModel streamingChatModel) {
        this.chatModel = chatModel;
        this.streamingChatModel = streamingChatModel;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. Простейший чат: только user-сообщение
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Простой запрос: один пользовательский вопрос → один ответ.
     * Системный промпт не задаём — модель использует своё дефолтное поведение.
     * <p>
     * Пример:
     * <pre>{@code
     * curl -X POST http://localhost:8081/api/chat \
     *   -H 'Content-Type: application/json' \
     *   -d '{"message":"Что такое ИИ-агент?"}'
     * }</pre>
     */
    @PostMapping
    public ChatResponse simpleChat(@RequestBody SimpleChatRequest request) {
        validateNotBlank(request == null ? null : request.message(), "message");

        // Здесь можно было бы вызвать удобный шорткат chatModel.chat(String) → String,
        // но мы используем List<ChatMessage> единым стилем для всех трёх эндпоинтов —
        // потом легче дорастать до памяти диалога (Этап 2) и инструментов (Этап 5).
        var messages = List.<ChatMessage>of(UserMessage.from(request.message()));

        var response = chatModel.chat(messages);
        return toChatResponse(response);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. Чат с системным промптом
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Запрос с системным промптом. Системный промпт идёт <b>первым</b> в списке сообщений —
     * это конвенция всех современных LLM. Если {@code system} пустой, отправляем только user.
     * <p>
     * Пример:
     * <pre>{@code
     * curl -X POST http://localhost:8081/api/chat/with-system \
     *   -H 'Content-Type: application/json' \
     *   -d '{
     *     "system":"Ты строгий технический рецензент. Отвечай по делу.",
     *     "user":"В одном абзаце: чем ИИ-агент отличается от обычного чат-бота на LLM?"
     *   }'
     * }</pre>
     */
    @PostMapping("/with-system")
    public ChatResponse chatWithSystem(@RequestBody ChatRequest request) {
        validateNotBlank(request == null ? null : request.user(), "user");

        var messages = buildMessages(request);
        var response = chatModel.chat(messages);
        return toChatResponse(response);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. Стриминг ответа через Server-Sent Events
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Стриминговый запрос. Ответ модели приходит частями через колбэк
     * {@link StreamingChatResponseHandler}, и мы транслируем каждую часть клиенту
     * как отдельное событие SSE.
     * <p>
     * <b>SseEmitter в Spring MVC.</b> {@link SseEmitter} с таймаутом {@code 0L} означает
     * «без таймаута» — поток будет жить, пока модель не закончит. В реальном проекте
     * стоит ставить разумный лимит (несколько минут) и обрабатывать обрыв клиента.
     * <p>
     * <b>Поток событий клиенту:</b>
     * <ul>
     *     <li>несколько событий вида {@code data: <часть текста>} — частичный ответ;</li>
     *     <li>финальное событие {@code event: done} с метаданными о токенах.</li>
     * </ul>
     * Пример:
     * <pre>{@code
     * curl -N -X POST http://localhost:8081/api/chat/stream \
     *   -H 'Content-Type: application/json' \
     *   -d '{"system":"Отвечай кратко.","user":"Перечисли 5 компонентов ИИ-агента."}'
     * }</pre>
     */
    @PostMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody ChatRequest request) {
        validateNotBlank(request == null ? null : request.user(), "user");

        // 0L = бесконечный таймаут на стороне Spring. На стороне модели таймаут уже
        // настроен в LlmConfig (Duration.ofMinutes(10)).
        SseEmitter emitter = new SseEmitter(0L);

        var messages = buildMessages(request);

        // Стриминговая модель не возвращает результат — она зовёт колбэки на каждом
        // куске ответа. Внутри колбэков мы пересылаем данные в SseEmitter.
        streamingChatModel.chat(messages, new StreamingChatResponseHandler() {

            /** Очередной кусок текста — пересылаем клиенту как SSE-data. */
            @Override
            public void onPartialResponse(String partial) {
                try {
                    emitter.send(SseEmitter.event().data(partial));
                } catch (IOException e) {
                    // Клиент мог отвалиться — закрываем стрим с ошибкой.
                    emitter.completeWithError(e);
                }
            }

            /** Генерация завершена — отдаём финальное событие с метаданными и закрываем стрим. */
            @Override
            public void onCompleteResponse(dev.langchain4j.model.chat.response.ChatResponse complete) {
                try {
                    TokenUsage usage = complete.tokenUsage();
                    String summary = usage != null
                            ? "input=" + usage.inputTokenCount() + ", output=" + usage.outputTokenCount()
                            : "tokenUsage=unavailable";
                    emitter.send(SseEmitter.event().name("done").data(summary));
                } catch (IOException ignored) {
                    // Если клиент уже отключился — в done-событии нет смысла.
                }
                emitter.complete();
            }

            /** Ошибка модели/сети — пробрасываем клиенту через emitter. */
            @Override
            public void onError(Throwable error) {
                emitter.completeWithError(error);
            }
        });

        return emitter;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Собирает список сообщений из {@link ChatRequest}. Системное сообщение добавляется
     * только если непустое. Порядок важен: system всегда впереди user.
     */
    private List<ChatMessage> buildMessages(ChatRequest request) {
        List<ChatMessage> messages = new ArrayList<>(2);
        if (request.system() != null && !request.system().isBlank()) {
            messages.add(SystemMessage.from(request.system()));
        }
        messages.add(UserMessage.from(request.user()));
        return messages;
    }

    /**
     * Преобразует «сырой» ответ LangChain4j в наш REST-DTO. Изолирует слой web от
     * внутренних классов библиотеки — если в будущем сменим LLM-библиотеку, ломаться
     * будет только этот метод.
     */
    private ChatResponse toChatResponse(dev.langchain4j.model.chat.response.ChatResponse response) {
        TokenUsage usage = response.tokenUsage();
        return new ChatResponse(
                response.aiMessage().text(),
                usage != null ? usage.inputTokenCount() : null,
                usage != null ? usage.outputTokenCount() : null,
                response.finishReason() != null ? response.finishReason().name() : null
        );
    }

    /** Простая ручная валидация. На Этапе 3 заменим на jakarta.validation + @Valid. */
    private void validateNotBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Field '" + fieldName + "' is required and must not be blank");
        }
    }
}
