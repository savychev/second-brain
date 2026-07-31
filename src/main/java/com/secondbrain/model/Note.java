package com.secondbrain.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Одна захваченная мысль.
 *
 * <p>Неизменяемая запись. Сериализуется в JSON как есть (см. {@code JsonNoteRepository}).
 *
 * @param id        уникальный идентификатор (UUID)
 * @param text      исходный текст мысли, ровно как ввёл пользователь
 * @param type      категория, присвоенная классификатором
 * @param createdAt момент захвата (UTC)
 * @param tags      извлечённые теги (например, из #hashtag); никогда не {@code null}
 * @param source    откуда пришла мысль: "console", позже "api" / "telegram"
 * @param notionPageId id страницы в Notion, если заметка уже отправлена;
 *                     {@code null} — ещё в очереди на отправку
 */
public record Note(
        String id,
        String text,
        NoteType type,
        Instant createdAt,
        List<String> tags,
        String source,
        String notionPageId
) {
    public Note {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(createdAt, "createdAt");
        // Защищаемся от null и от изменяемого списка снаружи.
        tags = (tags == null) ? List.of() : List.copyOf(tags);
        source = (source == null) ? "unknown" : source;
    }

    /**
     * Создаёт новую заметку с новым id и текущим временем.
     * Заметка ещё не отправлена в Notion ({@code notionPageId == null}).
     */
    public static Note create(String text, NoteType type, List<String> tags, String source) {
        return new Note(
                UUID.randomUUID().toString(),
                text,
                type,
                Instant.now(),
                tags,
                source,
                null
        );
    }

    /**
     * Отправлена ли заметка в Notion.
     *
     * <p>{@code @JsonIgnore} — это вычисляемое значение, а не поле: в JSON
     * хранится только {@code notionPageId}, из которого оно выводится.
     */
    @JsonIgnore
    public boolean isSynced() {
        return notionPageId != null && !notionPageId.isBlank();
    }

    /** Возвращает копию заметки, помеченную как отправленную в Notion. */
    public Note withNotionPageId(String pageId) {
        return new Note(id, text, type, createdAt, tags, source, pageId);
    }
}
