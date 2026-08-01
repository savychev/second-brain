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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Перенос заметок из JSON в SQLite.
 *
 * <p>Главное, что здесь проверяется — не «данные скопировались», а «в Notion не появятся
 * дубликаты». Ради этого тест {@link #reImportAfterSyncDoesNotResurrectTheNote()}
 * воспроизводит ровно ту последовательность, которая была найдена проверкой
 * и создавала вторую страницу.
 */
class JsonToSqliteImporterTest {

    @TempDir
    Path dir;

    private Path jsonFile;
    private NoteRepository json;
    private NoteRepository sqlite;

    private static final Instant T1 = Instant.parse("2026-07-27T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-07-27T11:00:00.305123200Z");

    @BeforeEach
    void setUp() {
        jsonFile = dir.resolve("notes.json");
        json = new JsonNoteRepository(jsonFile);
        sqlite = Storages.create(Storages.SQLITE, jsonFile, dir.resolve("second-brain.db"));
    }

    private static Note note(String id, String text, NoteType type, Instant at,
                             List<String> tags, String pageId) {
        return new Note(id, text, type, at, tags, "console", pageId);
    }

    @Test
    @DisplayName("Заметки переносятся со всеми полями, без изменений")
    void copiesEveryFieldVerbatim() {
        Note original = note("n1", "надо купить хлеб #дом", NoteType.TASK, T2,
                List.of("дом"), "notion-page-1");
        json.save(original);

        JsonToSqliteImporter.importAll(jsonFile, sqlite);

        Note imported = sqlite.findAll().get(0);
        assertEquals(original.id(), imported.id(), "id обязан сохраниться — это ключ от всего");
        assertEquals(original.text(), imported.text());
        assertEquals(original.type(), imported.type());
        assertEquals(original.createdAt(), imported.createdAt(), "время до наносекунд");
        assertEquals(original.tags(), imported.tags());
        assertEquals(original.source(), imported.source());
        assertEquals(original.notionPageId(), imported.notionPageId());
    }

    @Test
    @DisplayName("Отправленные заметки не попадают в очередь — иначе будут дубликаты")
    void syncedNotesStayOutOfTheQueue() {
        json.save(note("n1", "уже в Notion", NoteType.NOTE, T1, List.of(), "notion-page-1"));
        json.save(note("n2", "ещё не отправлена", NoteType.NOTE, T2, List.of(), null));

        JsonToSqliteImporter.Report report = JsonToSqliteImporter.importAll(jsonFile, sqlite);

        assertTrue(report.pageIdsPreserved());
        assertEquals(0, report.lostPageIds());
        assertEquals(List.of("n2"), sqlite.findUnsynced().stream().map(Note::id).toList(),
                "в очереди должна остаться только неотправленная заметка");
    }

    @Test
    @DisplayName("ТОТ САМЫЙ сценарий: повторный импорт после досылки не воскрешает заметку")
    void reImportAfterSyncDoesNotResurrectTheNote() {
        // 1. Notion был недоступен — заметка захвачена без id страницы.
        json.save(note("n1", "мысль", NoteType.NOTE, T1, List.of(), null));

        // 2. Первый импорт: в базе заметка тоже без id страницы.
        JsonToSqliteImporter.importAll(jsonFile, sqlite);
        assertEquals(1, sqlite.findUnsynced().size(), "пока всё верно: заметка ждёт отправки");

        // 3. Notion починился, досылка прошла — id страницы записан В JSON.
        json.markSynced("n1", "notion-page-1");

        // 4. Повторный импорт. Здесь ломалась прежняя версия: save() с ON CONFLICT
        //    DO NOTHING отбрасывал входящую строку вместе с id страницы.
        JsonToSqliteImporter.Report report = JsonToSqliteImporter.importAll(jsonFile, sqlite);

        // 5. Проверка: заметка НЕ должна считаться неотправленной.
        assertEquals("notion-page-1", sqlite.findAll().get(0).notionPageId(),
                "id страницы обязан доехать при повторном импорте");
        assertTrue(sqlite.findUnsynced().isEmpty(),
                "иначе досылка создала бы вторую страницу той же мысли");
        assertTrue(report.pageIdsPreserved());
        assertEquals(0, report.lostPageIds());
    }

    @Test
    @DisplayName("Импорт идемпотентен: сколько ни повторяй, записей не прибавится")
    void isIdempotent() {
        json.save(note("n1", "первая", NoteType.NOTE, T1, List.of(), "page-1"));
        json.save(note("n2", "вторая", NoteType.IDEA, T2, List.of("проект"), null));

        JsonToSqliteImporter.importAll(jsonFile, sqlite);
        JsonToSqliteImporter.importAll(jsonFile, sqlite);
        JsonToSqliteImporter.Report third = JsonToSqliteImporter.importAll(jsonFile, sqlite);

        assertEquals(2, sqlite.count());
        assertEquals(2, third.read());
        assertEquals(0, third.inserted(), "на третий раз добавлять уже нечего");
        assertEquals(2, third.alreadyPresent());
    }

    @Test
    @DisplayName("Исходный файл не изменяется — откат остаётся возможен")
    void sourceFileIsUntouched() {
        json.save(note("n1", "мысль", NoteType.NOTE, T1, List.of(), null));
        List<Note> before = json.findAll();

        JsonToSqliteImporter.importAll(jsonFile, sqlite);

        assertEquals(before, json.findAll(), "импорт обязан быть операцией только на чтение");
    }

    @Test
    @DisplayName("Отсутствующий файл — не ошибка, а пустой результат")
    void missingSourceIsHarmless() {
        JsonToSqliteImporter.Report report =
                JsonToSqliteImporter.importAll(dir.resolve("нет-такого.json"), sqlite);

        assertEquals(0, report.read());
        assertTrue(report.pageIdsPreserved());
        assertEquals(0, sqlite.count());
    }

    @Test
    @DisplayName("Потеря признака отправки обнаруживается и сообщается")
    void detectsLostPageIds() {
        json.save(note("n1", "отправленная", NoteType.NOTE, T1, List.of(), "page-1"));

        // Хранилище, теряющее отметку об отправке — имитация дефекта.
        NoteRepository broken = new JsonNoteRepository(dir.resolve("broken.json")) {
            @Override
            public void markSynced(String noteId, String notionPageId) {
                // молча ничего не делает
            }

            @Override
            public void save(Note note) {
                super.save(note.withNotionPageId(null));
            }
        };

        JsonToSqliteImporter.Report report = JsonToSqliteImporter.importAll(jsonFile, broken);

        assertFalse(report.pageIdsPreserved(), "потеря обязана быть замечена");
        assertEquals(1, report.lostPageIds());
    }

    @Test
    @DisplayName("Кириллица и теги переживают перенос")
    void cyrillicAndTagsSurvive() {
        json.save(note("n1", "полить цветы ёлки ёжик #дом #быт", NoteType.TASK, T1,
                List.of("дом", "быт"), null));

        JsonToSqliteImporter.importAll(jsonFile, sqlite);

        Note imported = sqlite.findAll().get(0);
        assertEquals("полить цветы ёлки ёжик #дом #быт", imported.text());
        assertEquals(List.of("дом", "быт"), imported.tags());
    }
}
