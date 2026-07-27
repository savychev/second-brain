package com.secondbrain.model;

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
 */
public record Note(
        String id,
        String text,
        NoteType type,
        Instant createdAt,
        List<String> tags,
        String source
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
     */
    public static Note create(String text, NoteType type, List<String> tags, String source) {
        return new Note(
                UUID.randomUUID().toString(),
                text,
                type,
                Instant.now(),
                tags,
                source
        );
    }
}
