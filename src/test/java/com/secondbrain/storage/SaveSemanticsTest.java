package com.secondbrain.storage;

import com.secondbrain.model.Note;
import com.secondbrain.model.NoteType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Фиксирует точный смысл {@code save()} — и ловушку, которая из него следует.
 *
 * <p>Проверка нашла реальный дефект в замысле импорта (шаг 4), и эти тесты
 * существуют, чтобы он не вернулся.
 *
 * <p><b>Суть ловушки.</b> В SQLite {@code save()} написан как
 * {@code ON CONFLICT(id) DO NOTHING}: если заметка с таким id уже есть, входящая
 * строка отбрасывается ЦЕЛИКОМ — вместе с {@code notionPageId}. Отсюда следует, что
 * <b>повторным вызовом {@code save()} нельзя «долить» появившийся id страницы</b>.
 *
 * <p>Почему это опасно именно здесь: очередь досылки в Notion определяется ровно
 * одним признаком — {@code notion_page_id IS NULL}. Заметка, потерявшая этот признак
 * при импорте, будет считаться неотправленной, и в Notion появится ВТОРАЯ страница
 * той же мысли.
 *
 * <p><b>Как правильно.</b> Импортёр обязан после сохранения явно вызвать
 * {@link NoteRepository#markSynced}, у которого нужная семантика уже есть
 * («проставить, но никогда не перезаписывать»). См. {@code JsonToSqliteImporter}.
 */
class SaveSemanticsTest {

    @TempDir
    Path dir;

    private NoteRepository sqlite;

    private static final Instant T = Instant.parse("2026-07-27T10:00:00Z");

    @BeforeEach
    void setUp() {
        sqlite = Storages.create(Storages.SQLITE, dir.resolve("notes.json"), dir.resolve("test.db"));
    }

    private static Note note(String id, String pageId) {
        return new Note(id, "мысль " + id, NoteType.NOTE, T, List.of(), "console", pageId);
    }

    @Test
    @DisplayName("ЛОВУШКА: повторный save() НЕ доливает появившийся id страницы")
    void saveCannotHealMissingPageId() {
        sqlite.save(note("n1", null));            // импорт, пока заметка не отправлена
        sqlite.save(note("n1", "notion-page-1")); // повторный импорт, id уже известен

        assertNull(sqlite.findAll().get(0).notionPageId(),
                "ON CONFLICT DO NOTHING отбрасывает входящую строку целиком");
        assertEquals(1, sqlite.findUnsynced().size(),
                "именно здесь рождается дубль страницы в Notion: заметка снова считается неотправленной");
    }

    @Test
    @DisplayName("Правильный способ долить id страницы — markSynced()")
    void markSyncedHealsMissingPageId() {
        sqlite.save(note("n1", null));

        // Так обязан поступать импортёр.
        sqlite.markSynced("n1", "notion-page-1");

        assertEquals("notion-page-1", sqlite.findAll().get(0).notionPageId());
        assertEquals(0, sqlite.findUnsynced().size(), "заметка не должна попасть в очередь повторно");
    }

    @Test
    @DisplayName("markSynced() никогда не затирает уже известный id страницы")
    void markSyncedNeverOverwrites() {
        sqlite.save(note("n1", "настоящая-страница"));

        sqlite.markSynced("n1", "другая-страница");

        assertEquals("настоящая-страница", sqlite.findAll().get(0).notionPageId(),
                "перезапись означала бы потерю ссылки на реально существующую страницу");
    }

    @Test
    @DisplayName("save() с id страницы сразу — заметка не попадает в очередь")
    void saveWithPageIdSkipsQueue() {
        sqlite.save(note("n1", "notion-page-1"));

        assertEquals(0, sqlite.findUnsynced().size());
        assertEquals("notion-page-1", sqlite.findAll().get(0).notionPageId());
    }

    @Test
    @DisplayName("save() не затирает уже проставленный id пустым значением")
    void saveDoesNotClearPageId() {
        sqlite.save(note("n1", "notion-page-1"));
        sqlite.save(note("n1", null));

        assertEquals("notion-page-1", sqlite.findAll().get(0).notionPageId());
        assertEquals(0, sqlite.findUnsynced().size());
    }
}
