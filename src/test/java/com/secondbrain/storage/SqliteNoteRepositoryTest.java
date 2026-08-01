package com.secondbrain.storage;

import com.secondbrain.model.Note;
import com.secondbrain.model.NoteType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Хранилище SQLite: общий контракт плюс то, что специфично именно для базы.
 */
class SqliteNoteRepositoryTest extends NoteRepositoryContractTest {

    @TempDir
    Path dir;

    private DataSource dataSource;
    private SqliteNoteRepository repository;

    @BeforeEach
    void setUp() {
        dataSource = SqliteFiles.dataSource(dir.resolve("test.db"));
        SqliteSchema.apply(dataSource);
        repository = new SqliteNoteRepository(dataSource);
    }

    @AfterEach
    void tearDown() {
        // На Windows @TempDir не удалится, пока файл базы держат открытым,
        // а рядом лежат служебные файлы -wal и -shm.
        dataSource = null;
        repository = null;
        System.gc();
    }

    @Override
    protected NoteRepository repository() {
        return repository;
    }

    private static Note note(String id, String text, NoteType type, String notionPageId) {
        return new Note(id, text, type, Instant.parse("2026-07-27T10:00:00Z"),
                List.of(), "console", notionPageId);
    }

    @Test
    @DisplayName("Схема создаётся идемпотентно: повторный запуск ничего не ломает")
    void schemaIsIdempotent() {
        repository.save(note("n1", "мысль", NoteType.NOTE, null));

        SqliteSchema.apply(dataSource);   // как при следующем запуске приложения
        SqliteSchema.apply(dataSource);

        assertEquals(1, repository.count(), "данные должны пережить повторное применение схемы");
    }

    @Test
    @DisplayName("Повторное сохранение с тем же id не создаёт дубликат")
    void saveIsIdempotent() {
        Note original = note("n1", "мысль", NoteType.NOTE, null);

        repository.save(original);
        repository.save(original);
        repository.save(original);

        assertEquals(1, repository.count(),
                "ON CONFLICT DO NOTHING позволяет гонять импорт сколько угодно раз");
    }

    @Test
    @DisplayName("markSynced() не перезаписывает уже проставленный id страницы")
    void markSyncedDoesNotOverwrite() {
        repository.save(note("n1", "мысль", NoteType.NOTE, "первая-страница"));

        repository.markSynced("n1", "вторая-страница");

        assertEquals("первая-страница", repository.findAll().get(0).notionPageId(),
                "перезапись означала бы потерю ссылки на реальную страницу Notion");
    }

    @Test
    @DisplayName("Пустая строка в id страницы приводится к NULL — иначе заметка выпала бы из очереди")
    void blankPageIdBecomesNull() {
        repository.save(note("n1", "мысль", NoteType.NOTE, "   "));

        assertEquals(1, repository.findUnsynced().size(),
                "isSynced() считает пустую строку «не отправлено» — база должна считать так же");
    }

    @Test
    @DisplayName("Время хранится строкой постоянной ширины — иначе сортировка врёт")
    void timestampsAreFixedWidth() throws SQLException {
        repository.save(new Note("n1", "ровно секунда", NoteType.NOTE,
                Instant.parse("2026-07-27T18:59:06Z"), List.of(), "console", null));
        repository.save(new Note("n2", "с наносекундами", NoteType.NOTE,
                Instant.parse("2026-07-27T18:59:06.305123200Z"), List.of(), "console", null));

        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT created_at FROM notes ORDER BY id")) {
            rs.next();
            String first = rs.getString(1);
            rs.next();
            String second = rs.getString(1);

            assertEquals(first.length(), second.length(),
                    "строки времени обязаны быть одной длины");
            assertTrue(first.compareTo(second) < 0,
                    "строковый порядок должен совпадать с хронологическим");
        }

        // И тот же порядок виден через findAll (от новых к старым).
        assertEquals(List.of("с наносекундами", "ровно секунда"),
                repository.findAll().stream().map(Note::text).toList());
    }

    @Test
    @DisplayName("Тип заметки в базе ограничен списком: мусор не запишется")
    void typeIsConstrained() throws SQLException {
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement()) {
            SQLException thrown = null;
            try {
                st.executeUpdate("""
                        INSERT INTO notes (id, text, type, created_at)
                        VALUES ('bad', 'мусор', 'НЕСУЩЕСТВУЮЩИЙ', '2026-07-27T10:00:00.000000000Z')
                        """);
            } catch (SQLException e) {
                thrown = e;
            }
            assertNotNull(thrown, "CHECK-ограничение должно отвергнуть неизвестный тип");
        }
    }

    @Test
    @DisplayName("База создаётся сама вместе с недостающими каталогами")
    void createsDatabaseAndParentDirectories() {
        Path nested = dir.resolve("глубже").resolve("ещё").resolve("brain.db");

        DataSource ds = SqliteFiles.dataSource(nested);
        SqliteSchema.apply(ds);
        NoteRepository repo = new SqliteNoteRepository(ds);
        repo.save(note("n1", "мысль", NoteType.NOTE, null));

        assertTrue(Files.exists(nested), "файл базы должен появиться: " + nested);
        assertEquals(1, repo.count());
    }

    @Test
    @DisplayName("Данные переживают переоткрытие базы")
    void dataSurvivesReopen() {
        Path db = dir.resolve("persist.db");
        DataSource first = SqliteFiles.dataSource(db);
        SqliteSchema.apply(first);
        new SqliteNoteRepository(first).save(note("n1", "надо не потеряться", NoteType.TASK, null));

        // Как будто приложение перезапустили.
        DataSource second = SqliteFiles.dataSource(db);
        SqliteSchema.apply(second);
        NoteRepository reopened = new SqliteNoteRepository(second);

        assertEquals(1, reopened.count());
        assertEquals("надо не потеряться", reopened.findAll().get(0).text());
    }
}
