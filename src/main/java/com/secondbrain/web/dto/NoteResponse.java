package com.secondbrain.web.dto;

import com.secondbrain.model.Note;
import com.secondbrain.model.NoteType;

import java.time.Instant;
import java.util.List;

/**
 * Заметка в ответе API.
 *
 * <p>Отдельный тип, а не сам {@link Note}, по двум причинам. Во-первых, внутренняя
 * модель может меняться, не ломая клиентов. Во-вторых, здесь видно «отправлена ли
 * в Notion» как понятный флаг, а не как «поле не пустое».
 */
public record NoteResponse(
        String id,
        String text,
        NoteType type,
        Instant createdAt,
        List<String> tags,
        String source,
        boolean synced,
        String notionPageId
) {
    public static NoteResponse from(Note note) {
        return new NoteResponse(
                note.id(),
                note.text(),
                note.type(),
                note.createdAt(),
                note.tags(),
                note.source(),
                note.isSynced(),
                note.notionPageId());
    }

    public static List<NoteResponse> from(List<Note> notes) {
        return notes.stream().map(NoteResponse::from).toList();
    }
}
