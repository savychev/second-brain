package com.secondbrain.storage;

import com.secondbrain.model.Note;
import com.secondbrain.model.NoteType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Контракт хранилища заметок: проверки, которые обязана пройти любая реализация
 * {@link NoteRepository}.
 *
 * <p>Смысл в том, чтобы подмена хранилища (JSON → SQLite → в будущем JPA) не меняла
 * поведение приложения. Каждая реализация наследует этот класс и получает весь набор
 * проверок разом; специфика реализации проверяется отдельно в её собственном тесте.
 *
 * <p>Время задаётся явно, а не через {@link Note#create}, иначе тесты порядка
 * зависели бы от того, насколько быстро выполняются соседние строки.
 *
 * <p><b>Чего в контракте намеренно нет</b> — сценарии, где реализации расходятся:
 * <ul>
 *   <li>повторное сохранение заметки с тем же id: JSON добавит вторую запись,
 *       SQLite проигнорирует;</li>
 *   <li>{@code markSynced} поверх уже отправленной: JSON перезапишет,
 *       SQLite оставит прежний id страницы.</li>
 * </ul>
 * Эти случаи проверяются в {@code SqliteNoteRepositoryTest}.
 */
abstract class NoteRepositoryContractTest {

    /** Пустое хранилище для очередного теста. */
    protected abstract NoteRepository repository();

    private static final Instant T1 = Instant.parse("2026-07-27T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-07-27T11:00:00.500Z");
    private static final Instant T3 = Instant.parse("2026-07-27T12:00:00.305123200Z");

    private static Note note(String id, String text, NoteType type, Instant at,
                             List<String> tags, String notionPageId) {
        return new Note(id, text, type, at, tags, "console", notionPageId);
    }

    @Test
    @DisplayName("Сохранённая заметка читается со всеми полями")
    void savesAndReadsAllFields() {
        NoteRepository repo = repository();
        Note original = note("n1", "надо купить хлеб #дом", NoteType.TASK, T1,
                List.of("дом"), null);

        repo.save(original);
        List<Note> all = repo.findAll();

        assertEquals(1, all.size());
        Note read = all.get(0);
        assertEquals(original.id(), read.id());
        assertEquals(original.text(), read.text(), "кириллица должна сохраняться как есть");
        assertEquals(original.type(), read.type());
        assertEquals(original.createdAt(), read.createdAt(), "время до наносекунд");
        assertEquals(original.tags(), read.tags());
        assertEquals(original.source(), read.source());
        assertFalse(read.isSynced());
    }

    @Test
    @DisplayName("Время с наносекундами не теряет точность")
    void preservesNanoseconds() {
        NoteRepository repo = repository();
        repo.save(note("n1", "мысль", NoteType.NOTE, T3, List.of(), null));

        assertEquals(T3, repo.findAll().get(0).createdAt());
    }

    @Test
    @DisplayName("Список идёт от новых к старым независимо от порядка сохранения")
    void findAllIsNewestFirst() {
        NoteRepository repo = repository();
        // Сохраняем вразнобой: порядок должен задаваться временем, а не вставкой.
        repo.save(note("n2", "вторая", NoteType.NOTE, T2, List.of(), null));
        repo.save(note("n3", "третья", NoteType.NOTE, T3, List.of(), null));
        repo.save(note("n1", "первая", NoteType.NOTE, T1, List.of(), null));

        List<Note> all = repo.findAll();

        assertEquals(List.of("третья", "вторая", "первая"),
                all.stream().map(Note::text).toList());
    }

    @Test
    @DisplayName("Фильтр по типу возвращает только свой тип")
    void findByTypeFilters() {
        NoteRepository repo = repository();
        repo.save(note("n1", "задача 1", NoteType.TASK, T1, List.of(), null));
        repo.save(note("n2", "задача 2", NoteType.TASK, T2, List.of(), null));
        repo.save(note("n3", "ссылка", NoteType.LINK, T3, List.of(), null));

        assertEquals(2, repo.findByType(NoteType.TASK).size());
        assertEquals(1, repo.findByType(NoteType.LINK).size());
        assertTrue(repo.findByType(NoteType.IDEA).isEmpty());
    }

    @Test
    @DisplayName("count() считает все заметки")
    void countsAll() {
        NoteRepository repo = repository();
        assertEquals(0, repo.count());

        repo.save(note("n1", "раз", NoteType.NOTE, T1, List.of(), null));
        repo.save(note("n2", "два", NoteType.IDEA, T2, List.of(), null));

        assertEquals(2, repo.count());
    }

    @Test
    @DisplayName("countByType() даёт счётчик на каждый тип, включая нулевые")
    void countsByType() {
        NoteRepository repo = repository();
        repo.save(note("n1", "задача", NoteType.TASK, T1, List.of(), null));
        repo.save(note("n2", "идея", NoteType.IDEA, T2, List.of(), null));
        repo.save(note("n3", "ещё задача", NoteType.TASK, T3, List.of(), null));

        Map<NoteType, Long> counts = repo.countByType();

        assertEquals(2L, counts.get(NoteType.TASK));
        assertEquals(1L, counts.get(NoteType.IDEA));
        assertEquals(0L, counts.get(NoteType.LINK), "типы без заметок должны давать 0, а не null");
        assertEquals(0L, counts.get(NoteType.NOTE));
    }

    @Test
    @DisplayName("Очередь досылки: только неотправленные, от старых к новым")
    void findUnsyncedOldestFirst() {
        NoteRepository repo = repository();
        repo.save(note("n1", "старая неотправленная", NoteType.NOTE, T1, List.of(), null));
        repo.save(note("n2", "отправленная", NoteType.NOTE, T2, List.of(), "notion-page-2"));
        repo.save(note("n3", "новая неотправленная", NoteType.NOTE, T3, List.of(), null));

        List<Note> pending = repo.findUnsynced();

        assertEquals(List.of("старая неотправленная", "новая неотправленная"),
                pending.stream().map(Note::text).toList(),
                "Notion должен получать заметки в порядке захвата");
    }

    @Test
    @DisplayName("markSynced() убирает заметку из очереди")
    void markSyncedRemovesFromQueue() {
        NoteRepository repo = repository();
        repo.save(note("n1", "мысль", NoteType.NOTE, T1, List.of(), null));
        assertEquals(1, repo.findUnsynced().size());

        repo.markSynced("n1", "notion-page-1");

        assertTrue(repo.findUnsynced().isEmpty());
        Note updated = repo.findAll().get(0);
        assertTrue(updated.isSynced());
        assertEquals("notion-page-1", updated.notionPageId());
    }

    @Test
    @DisplayName("Постраничный вывод отдаёт нужный кусок ленты")
    void findPageReturnsSlice() {
        NoteRepository repo = repository();
        repo.save(note("n1", "первая", NoteType.NOTE, T1, List.of(), null));
        repo.save(note("n2", "вторая", NoteType.NOTE, T2, List.of(), null));
        repo.save(note("n3", "третья", NoteType.NOTE, T3, List.of(), null));

        assertEquals(List.of("третья", "вторая"),
                repo.findPage(2, 0).stream().map(Note::text).toList());
        assertEquals(List.of("первая"),
                repo.findPage(2, 2).stream().map(Note::text).toList());
        assertTrue(repo.findPage(10, 99).isEmpty(), "выход за конец ленты — пустой список");
    }

    @Test
    @DisplayName("Пустое хранилище не бросает исключений")
    void emptyRepositoryIsSafe() {
        NoteRepository repo = repository();

        assertEquals(0, repo.count());
        assertTrue(repo.findAll().isEmpty());
        assertTrue(repo.findUnsynced().isEmpty());
        assertTrue(repo.findByType(NoteType.TASK).isEmpty());
        assertTrue(repo.findPage(10, 0).isEmpty());
    }

    @Test
    @DisplayName("Заметка без тегов возвращает пустой список, а не null")
    void emptyTagsAreEmptyList() {
        NoteRepository repo = repository();
        repo.save(note("n1", "без тегов", NoteType.NOTE, T1, List.of(), null));

        assertEquals(List.of(), repo.findAll().get(0).tags());
    }

    @Test
    @DisplayName("Несколько тегов сохраняют порядок")
    void tagsKeepOrder() {
        NoteRepository repo = repository();
        repo.save(note("n1", "мысль #работа #срочно #дом", NoteType.NOTE, T1,
                List.of("работа", "срочно", "дом"), null));

        assertEquals(List.of("работа", "срочно", "дом"), repo.findAll().get(0).tags());
    }
}
