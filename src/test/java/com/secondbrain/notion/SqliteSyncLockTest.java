package com.secondbrain.notion;

import com.secondbrain.model.Note;
import com.secondbrain.model.NoteType;
import com.secondbrain.storage.NoteRepository;
import com.secondbrain.storage.SqliteFiles;
import com.secondbrain.storage.SqliteNoteRepository;
import com.secondbrain.storage.SqliteSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Межпроцессный замок на досылку.
 *
 * <p>Разные процессы имитируются <b>разными подключениями к одному файлу базы</b> —
 * это и есть то, что их роднит в реальности. Замок внутри программы такую пару
 * не разделил бы, поэтому проверка здесь настоящая, а не формальная.
 */
class SqliteSyncLockTest {

    @TempDir
    Path dir;

    private DataSource connectionA;
    private DataSource connectionB;

    @BeforeEach
    void setUp() {
        Path db = dir.resolve("second-brain.db");
        connectionA = SqliteFiles.dataSource(db);
        connectionB = SqliteFiles.dataSource(db);
        SqliteSchema.apply(connectionA);
    }

    @Test
    @DisplayName("Замок достаётся ровно одному из двух процессов")
    void onlyOneHolderWins() {
        SqliteSyncLock first = new SqliteSyncLock(connectionA);
        SqliteSyncLock second = new SqliteSyncLock(connectionB);

        assertTrue(first.tryAcquire(), "первый обязан захватить свободный замок");
        assertFalse(second.tryAcquire(), "второй обязан получить отказ, а не встать в очередь");
    }

    @Test
    @DisplayName("После освобождения замок достаётся следующему")
    void releasingLetsTheNextOneIn() {
        SqliteSyncLock first = new SqliteSyncLock(connectionA);
        SqliteSyncLock second = new SqliteSyncLock(connectionB);

        first.tryAcquire();
        first.release();

        assertTrue(second.tryAcquire());
    }

    @Test
    @DisplayName("Чужой замок не отпускается — иначе двое работали бы одновременно")
    void cannotReleaseSomeoneElsesLock() {
        SqliteSyncLock owner = new SqliteSyncLock(connectionA);
        SqliteSyncLock stranger = new SqliteSyncLock(connectionB);

        owner.tryAcquire();
        stranger.release();   // попытка отпустить не свой

        assertFalse(stranger.tryAcquire(), "замок обязан остаться у владельца");
    }

    @Test
    @DisplayName("Повторный захват тем же владельцем не проходит, пока замок занят")
    void notReentrant() {
        SqliteSyncLock lock = new SqliteSyncLock(connectionA);

        assertTrue(lock.tryAcquire());
        assertFalse(lock.tryAcquire(),
                "иначе вложенный вызов мог бы освободить замок раньше времени");
    }

    @Test
    @DisplayName("ГЛАВНОЕ: два процесса не создадут в Notion две страницы одной мысли")
    void twoProcessesDoNotDuplicatePages() {
        NoteRepository repository = new SqliteNoteRepository(connectionA);
        repository.save(new Note("n1", "мысль", NoteType.NOTE,
                Instant.parse("2026-07-27T10:00:00Z"), List.of(), "console", null));

        // Считаем страницы, созданные обоими «процессами».
        int[] pagesCreated = {0};
        NotionClient countingClient = note -> {
            pagesCreated[0]++;
            return "page-" + note.id();
        };

        // Два сервиса с разными подключениями — как сервер и консоль.
        NotionSyncService server = new NotionSyncService(countingClient, repository, true,
                new SqliteSyncLock(connectionA));
        NotionSyncService console = new NotionSyncService(countingClient, repository, true,
                new SqliteSyncLock(connectionB));

        int sentByServer = server.flushQueue();
        int sentByConsole = console.flushQueue();

        assertEquals(1, pagesCreated[0],
                "в Notion должна появиться ровно одна страница");
        assertEquals(1, sentByServer);
        assertEquals(0, sentByConsole, "второму уже нечего отправлять");
        assertTrue(repository.findUnsynced().isEmpty());
    }

    @Test
    @DisplayName("Занятый замок не роняет захват мысли — она просто ждёт")
    void busyLockJustQueuesTheNote() {
        NoteRepository repository = new SqliteNoteRepository(connectionA);
        Note note = new Note("n1", "мысль", NoteType.NOTE,
                Instant.parse("2026-07-27T10:00:00Z"), List.of(), "console", null);
        repository.save(note);

        // Кто-то другой уже держит замок.
        new SqliteSyncLock(connectionB).tryAcquire();

        NotionSyncService sync = new NotionSyncService(n -> "page-1", repository, true,
                new SqliteSyncLock(connectionA));
        NotionSyncService.SyncResult result = sync.trySync(note);

        assertEquals(NotionSyncService.SyncResult.Status.QUEUED, result.status());
        assertEquals(1, repository.findUnsynced().size(), "заметка обязана остаться в очереди");
    }
}
