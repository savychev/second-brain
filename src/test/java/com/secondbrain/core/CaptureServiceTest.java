package com.secondbrain.core;

import com.secondbrain.classify.RuleBasedClassifier;
import com.secondbrain.model.Note;
import com.secondbrain.model.NoteType;
import com.secondbrain.storage.JsonNoteRepository;
import com.secondbrain.storage.NoteRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptureServiceTest {

    @Test
    void captureClassifiesTagsAndPersists(@TempDir Path dir) {
        NoteRepository repo = new JsonNoteRepository(dir.resolve("notes.json"));
        CaptureService service = new CaptureService(new RuleBasedClassifier(), repo);

        CaptureService.Captured captured = service.capture("купить кофе #дом", "console");

        Note note = captured.note();
        assertEquals(NoteType.TASK, note.type());
        assertEquals("console", note.source());
        assertEquals(java.util.List.of("дом"), note.tags());
        assertNotNull(note.id());

        // Реально сохранилось.
        assertEquals(1, repo.count());
    }

    @Test
    void returnedNoteReflectsSuccessfulNotionSend(@TempDir Path dir) {
        NoteRepository repo = new JsonNoteRepository(dir.resolve("notes.json"));
        com.secondbrain.notion.NotionClient fakeNotion = n -> "page-" + n.id();
        com.secondbrain.notion.NotionSyncService sync =
                new com.secondbrain.notion.NotionSyncService(fakeNotion, repo, true);
        CaptureService service = new CaptureService(new RuleBasedClassifier(), repo, sync);

        CaptureService.Captured captured = service.capture("надо позвонить", "console");

        // Заметка неизменяемая: без явного обновления возвращённый объект
        // сообщал бы «не отправлено» о заметке, уже попавшей в Notion.
        assertTrue(captured.note().isSynced(),
                "возвращённая заметка обязана знать, что она уже в Notion");
        assertEquals("page-" + captured.note().id(), captured.note().notionPageId());
        assertTrue(repo.findAll().get(0).isSynced(), "и в хранилище тоже");
    }

    @Test
    void returnedNoteStaysUnsyncedWhenNotionFails(@TempDir Path dir) {
        NoteRepository repo = new JsonNoteRepository(dir.resolve("notes.json"));
        com.secondbrain.notion.NotionClient broken = n -> {
            throw new com.secondbrain.notion.NotionException("недоступен");
        };
        com.secondbrain.notion.NotionSyncService sync =
                new com.secondbrain.notion.NotionSyncService(broken, repo, true);
        CaptureService service = new CaptureService(new RuleBasedClassifier(), repo, sync);

        CaptureService.Captured captured = service.capture("мысль во время сбоя", "console");

        assertFalse(captured.note().isSynced());
        assertEquals(1, repo.findUnsynced().size(), "заметка обязана остаться в очереди");
    }
}
