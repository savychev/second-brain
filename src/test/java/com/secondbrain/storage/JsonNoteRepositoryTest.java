package com.secondbrain.storage;

import com.secondbrain.model.Note;
import com.secondbrain.model.NoteType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonNoteRepositoryTest {

    @Test
    void savesAndReloadsAcrossInstances(@TempDir Path dir) {
        Path file = dir.resolve("notes.json");

        // Первый «запуск»: сохраняем.
        NoteRepository writer = new JsonNoteRepository(file);
        writer.save(Note.create("купить хлеб", NoteType.TASK, List.of(), "console"));
        writer.save(Note.create("идея приложения", NoteType.IDEA, List.of("проект"), "console"));

        // Второй «запуск» с нуля: данные на месте (критерий приёмки P0 №3).
        NoteRepository reloaded = new JsonNoteRepository(file);
        assertEquals(2, reloaded.count());

        List<Note> all = reloaded.findAll();
        assertEquals(2, all.size());
        // Кириллица не побилась при сериализации.
        assertTrue(all.stream().anyMatch(n -> n.text().equals("купить хлеб")));
    }

    @Test
    void findByTypeFilters(@TempDir Path dir) {
        NoteRepository repo = new JsonNoteRepository(dir.resolve("notes.json"));
        repo.save(Note.create("задача 1", NoteType.TASK, List.of(), "console"));
        repo.save(Note.create("задача 2", NoteType.TASK, List.of(), "console"));
        repo.save(Note.create("ссылка", NoteType.LINK, List.of(), "console"));

        assertEquals(2, repo.findByType(NoteType.TASK).size());
        assertEquals(1, repo.findByType(NoteType.LINK).size());
        assertEquals(0, repo.findByType(NoteType.IDEA).size());
    }

    @Test
    void emptyRepositoryReturnsEmpty(@TempDir Path dir) {
        NoteRepository repo = new JsonNoteRepository(dir.resolve("does-not-exist-yet.json"));
        assertEquals(0, repo.count());
        assertTrue(repo.findAll().isEmpty());
    }

    @Test
    void newestFirst(@TempDir Path dir) throws InterruptedException {
        NoteRepository repo = new JsonNoteRepository(dir.resolve("notes.json"));
        repo.save(Note.create("первая", NoteType.NOTE, List.of(), "console"));
        Thread.sleep(5);
        repo.save(Note.create("вторая", NoteType.NOTE, List.of(), "console"));

        List<Note> all = repo.findAll();
        assertEquals("вторая", all.get(0).text());
        assertEquals("первая", all.get(1).text());
    }
}
