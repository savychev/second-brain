package com.secondbrain.storage;

import com.secondbrain.model.Note;
import com.secondbrain.model.NoteType;

import java.util.List;

/**
 * Локальное хранилище заметок.
 *
 * <p>Этап 1 — {@link JsonNoteRepository} (файл JSON). Этап 3 — реализация на SQLite.
 * Гарантия «ноль потерь» из PRD держится именно здесь: сначала запись в локальное
 * хранилище, и только потом (на этапе 2) отправка в Notion.
 */
public interface NoteRepository {

    /** Сохраняет заметку. */
    void save(Note note);

    /** Все заметки, от новых к старым. */
    List<Note> findAll();

    /** Заметки одного типа, от новых к старым. */
    List<Note> findByType(NoteType type);

    /** Количество сохранённых заметок. */
    long count();
}
