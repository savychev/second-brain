package com.secondbrain.notion;

import com.secondbrain.model.Note;
import com.secondbrain.model.NoteType;
import com.secondbrain.storage.JsonNoteRepository;
import com.secondbrain.storage.NoteRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Проверяет требование P0 №5: при недоступном Notion заметка не теряется,
 * а ждёт в локальной очереди и уходит после восстановления связи.
 */
class NotionSyncServiceTest {

    /** Заглушка вместо настоящего Notion: можно «уронить» и «поднять». */
    private static class FakeNotionClient implements NotionClient {
        boolean available = true;
        final List<String> sentNoteIds = new ArrayList<>();

        @Override
        public String createPage(Note note) throws NotionException {
            if (!available) {
                throw new NotionException("Notion недоступен (тест)");
            }
            sentNoteIds.add(note.id());
            return "page-" + note.id();
        }
    }

    private static Note note(String text, NoteType type) {
        return Note.create(text, type, List.of(), "console");
    }

    @Test
    @DisplayName("Успешная отправка помечает заметку как синхронизированную")
    void sendsAndMarks(@TempDir Path dir) {
        NoteRepository repo = new JsonNoteRepository(dir.resolve("notes.json"));
        FakeNotionClient client = new FakeNotionClient();
        NotionSyncService sync = new NotionSyncService(client, repo, true);

        Note n = note("купить хлеб", NoteType.TASK);
        repo.save(n);

        NotionSyncService.SyncResult result = sync.trySync(n);

        assertTrue(result.isSent());
        assertEquals(List.of(n.id()), client.sentNoteIds);
        assertTrue(repo.findAll().get(0).isSynced());
        assertEquals(0, sync.pendingCount());
    }

    @Test
    @DisplayName("Notion недоступен → заметка остаётся в очереди, ошибки наружу не летят")
    void keepsInQueueWhenNotionDown(@TempDir Path dir) {
        NoteRepository repo = new JsonNoteRepository(dir.resolve("notes.json"));
        FakeNotionClient client = new FakeNotionClient();
        client.available = false;
        NotionSyncService sync = new NotionSyncService(client, repo, true);

        Note n = note("идея приложения", NoteType.IDEA);
        repo.save(n);

        NotionSyncService.SyncResult result = sync.trySync(n);

        assertFalse(result.isSent());
        assertEquals(NotionSyncService.SyncResult.Status.QUEUED, result.status());
        // Заметка на месте и ждёт отправки.
        assertEquals(1, repo.count());
        assertEquals(1, sync.pendingCount());
        assertFalse(repo.findAll().get(0).isSynced());
    }

    @Test
    @DisplayName("После восстановления связи очередь досылается целиком")
    void flushesQueueAfterRecovery(@TempDir Path dir) {
        NoteRepository repo = new JsonNoteRepository(dir.resolve("notes.json"));
        FakeNotionClient client = new FakeNotionClient();
        NotionSyncService sync = new NotionSyncService(client, repo, true);

        // Notion лежит — копим три заметки.
        client.available = false;
        for (int i = 1; i <= 3; i++) {
            Note n = note("мысль " + i, NoteType.NOTE);
            repo.save(n);
            sync.trySync(n);
        }
        assertEquals(3, sync.pendingCount());

        // Notion поднялся.
        client.available = true;
        int sent = sync.flushQueue();

        assertEquals(3, sent);
        assertEquals(0, sync.pendingCount());
        assertEquals(3, client.sentNoteIds.size());
    }

    @Test
    @DisplayName("Выключенная интеграция ничего не отправляет и не мешает работе")
    void disabledIntegrationSkips(@TempDir Path dir) {
        NoteRepository repo = new JsonNoteRepository(dir.resolve("notes.json"));
        FakeNotionClient client = new FakeNotionClient();
        NotionSyncService sync = new NotionSyncService(client, repo, false);

        Note n = note("просто заметка", NoteType.NOTE);
        repo.save(n);

        NotionSyncService.SyncResult result = sync.trySync(n);

        assertEquals(NotionSyncService.SyncResult.Status.SKIPPED, result.status());
        assertTrue(client.sentNoteIds.isEmpty());
        assertEquals(0, sync.flushQueue());
    }

    @Test
    @DisplayName("Досылка останавливается на первой неудаче — не долбит недоступный Notion")
    void stopsOnFirstFailure(@TempDir Path dir) {
        NoteRepository repo = new JsonNoteRepository(dir.resolve("notes.json"));
        FakeNotionClient client = new FakeNotionClient();
        client.available = false;
        NotionSyncService sync = new NotionSyncService(client, repo, true);

        for (int i = 1; i <= 5; i++) {
            repo.save(note("мысль " + i, NoteType.NOTE));
        }

        assertEquals(0, sync.flushQueue());
        assertTrue(client.sentNoteIds.isEmpty());
        assertEquals(5, sync.pendingCount());
    }
}
