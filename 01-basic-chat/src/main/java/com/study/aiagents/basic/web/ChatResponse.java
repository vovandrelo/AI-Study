package com.study.aiagents.basic.web;

/**
 * DTO ответа для синхронных эндпоинтов ({@code /api/chat} и {@code /api/chat/with-system}).
 * <p>
 * Содержит как сам текст ответа, так и метаданные генерации. Метаданные полезны для:
 * <ul>
 *     <li>контроля стоимости (на платных моделях платим за {@code inputTokens + outputTokens});</li>
 *     <li>детекции обрезанного ответа: {@code finishReason == "LENGTH"} означает,
 *         что модель упёрлась в лимит {@code maxTokens} и не закончила мысль;</li>
 *     <li>отладки промптов: сравниваем количество токенов «до и после» оптимизации.</li>
 * </ul>
 * <p>
 * Поля {@code inputTokens} / {@code outputTokens} / {@code finishReason} помечены как
 * {@code Integer} / {@code String} (а не примитивы), потому что некоторые провайдеры
 * могут не возвращать эти данные — тогда поле будет {@code null}.
 *
 * @param reply        текст ответа модели
 * @param inputTokens  количество токенов в запросе (system + user + история, если есть)
 * @param outputTokens количество токенов в ответе модели
 * @param finishReason причина остановки генерации:
 *                     {@code "STOP"} (нормальное завершение),
 *                     {@code "LENGTH"} (исчерпан maxTokens),
 *                     {@code "TOOL_EXECUTION"} (модель захотела вызвать инструмент — Этап 5),
 *                     {@code "CONTENT_FILTER"} (срабатывание модерации) и т.д.
 */
public record ChatResponse(
        String reply,
        Integer inputTokens,
        Integer outputTokens,
        String finishReason
) {
}
