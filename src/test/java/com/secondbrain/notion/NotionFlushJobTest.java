package com.secondbrain.notion;

import com.secondbrain.model.Note;
import com.secondbrain.model.NoteType;
import com.secondbrain.storage.JsonNoteRepository;
import com.secondbrain.storage.NoteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Фоновая досылка очереди. */
class NotionFlushJobTest {

    private static class FakeNotionClient implements NotionClient {
        boolean available = true;
        final List<String> sent = new ArrayList<>();

        @Override
        public String createPage(Note note) throws NotionException {
            if (!available) {
                throw new NotionException("Notion недоступен (тест)");
            }
            sent.add(note.id());
            return "page-" + note.id();
        }
    }

    @TempDir
    Path dir;

    private NoteRepository repository;
    private FakeNotionClient client;

    @BeforeEach
    void setUp() {
        repository = new JsonNoteRepository(dir.resolve("notes.json"));
        client = new FakeNotionClient();
    }

    private void pending(String id) {
        repository.save(new Note(id, "мысль " + id, NoteType.NOTE,
                Instant.parse("2026-07-27T10:00:00Z"), List.of(), "console", null));
    }

    private NotionFlushJob job(boolean notionEnabled) {
        return new NotionFlushJob(new NotionSyncService(client, repository, notionEnabled));
    }

    @Test
    @DisplayName("Накопившаяся очередь уходит сама, без участия человека")
    void flushesPendingNotes() {
        pending("n1");
        pending("n2");

        job(true).flush();

        assertEquals(2, client.sent.size());
        assertTrue(repository.findUnsynced().isEmpty());
    }

    @Test
    @DisplayName("Пустая очередь — Notion не тревожим")
    void doesNothingWhenQueueIsEmpty() {
        job(true).flush();

        assertTrue(client.sent.isEmpty(), "лишних запросов к Notion быть не должно");
    }

    @Test
    @DisplayName("Выключенная интеграция — задача просто молчит")
    void doesNothingWhenNotionDisabled() {
        pending("n1");

        job(false).flush();

        assertTrue(client.sent.isEmpty());
        assertEquals(1, repository.findUnsynced().size());
    }

    @Test
    @DisplayName("Notion недоступен — заметки остаются в очереди, исключение наружу не летит")
    void survivesNotionOutage() {
        pending("n1");
        client.available = false;

        job(true).flush();   // не должно бросить

        assertEquals(1, repository.findUnsynced().size());
    }

    @Test
    @DisplayName("Заметка, попавшая в Notion, повторно не отправляется")
    void doesNotResendAlreadySyncedNotes() {
        pending("n1");
        NotionFlushJob flushJob = job(true);

        flushJob.flush();
        flushJob.flush();
        flushJob.flush();

        assertEquals(1, client.sent.size(), "иначе в Notion копились бы дубликаты страниц");
    }
}
