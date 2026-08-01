package com.secondbrain.storage;

import com.secondbrain.model.Note;
import com.secondbrain.model.NoteType;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

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

    /**
     * Заметки, ещё не отправленные в Notion — локальная очередь досылки.
     * Порядок: от старых к новым, чтобы Notion получил их в хронологии захвата.
     */
    List<Note> findUnsynced();

    /**
     * Помечает заметку как отправленную в Notion.
     *
     * @param noteId       локальный id заметки
     * @param notionPageId id созданной страницы в Notion
     */
    void markSynced(String noteId, String notionPageId);

    /**
     * Страница заметок от новых к старым — под постраничный вывод в REST API.
     *
     * <p>Реализация по умолчанию честная, но неэффективная: читает всё и отрезает
     * нужный кусок. Хранилищам, умеющим это на своей стороне (SQLite —
     * {@code LIMIT/OFFSET}), следует метод переопределить.
     *
     * @param limit  сколько заметок вернуть
     * @param offset сколько пропустить с начала
     */
    default List<Note> findPage(int limit, int offset) {
        return findAll().stream().skip(Math.max(0, offset)).limit(Math.max(0, limit)).toList();
    }

    /**
     * Количество заметок по каждому типу — под экран статистики.
     *
     * <p>Реализация по умолчанию делает по одному проходу на тип; SQLite умеет
     * посчитать всё одним запросом и метод переопределяет.
     */
    default Map<NoteType, Long> countByType() {
        Map<NoteType, Long> counts = new EnumMap<>(NoteType.class);
        for (NoteType type : NoteType.values()) {
            counts.put(type, (long) findByType(type).size());
        }
        return counts;
    }

    /**
     * Где именно лежат заметки — для показа пользователю.
     *
     * <p>Нужно на случай запуска из другого рабочего каталога: тогда хранилище
     * окажется пустым, и без указания пути это выглядит как «все заметки пропали».
     */
    default String describe() {
        return getClass().getSimpleName();
    }
}
