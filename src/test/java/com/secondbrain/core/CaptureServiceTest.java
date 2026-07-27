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
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
}
