package com.secondbrain.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Тело запроса на захват мысли.
 *
 * <p>Намеренно принимает <b>только текст и источник</b>, а не всю заметку. Если бы
 * приходил готовый {@code Note}, клиент мог бы прислать свой {@code notionPageId} —
 * и заметка считалась бы отправленной, никогда не побывав в Notion. Очередь досылки
 * определяется ровно этим полем, поэтому наружу его отдавать нельзя.
 *
 * <p>Остальное — id, время, тип, теги — определяет сервер.
 *
 * @param text   текст мысли
 * @param source откуда пришла: {@code "api"}, {@code "telegram"}; пусто → {@code "api"}
 */
public record CreateNoteRequest(

        @NotBlank(message = "Текст мысли не может быть пустым")
        @Size(max = 10_000, message = "Текст мысли длиннее 10000 символов")
        String text,

        @Size(max = 32, message = "Слишком длинное имя источника")
        String source
) {
    /** Источник по умолчанию, если клиент его не указал. */
    public String sourceOrDefault() {
        return (source == null || source.isBlank()) ? "api" : source.trim();
    }
}
