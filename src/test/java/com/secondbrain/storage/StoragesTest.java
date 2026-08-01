package com.secondbrain.storage;

import com.secondbrain.model.Note;
import com.secondbrain.model.NoteType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Выбор хранилища. Проверяем именно то, что делает переключатель опасным
 * при ошибке: молчаливый уход не на то хранилище выглядит как пропажа заметок.
 */
class StoragesTest {

    private Path json(Path dir) {
        return dir.resolve("notes.json");
    }

    private Path db(Path dir) {
        return dir.resolve("second-brain.db");
    }

    @Test
    @DisplayName("По умолчанию — SQLite (переключено на шаге 5)")
    void defaultsToSqlite(@TempDir Path dir) {
        assertInstanceOf(SqliteNoteRepository.class, Storages.create(null, json(dir), db(dir)));
        assertInstanceOf(SqliteNoteRepository.class, Storages.create("", json(dir), db(dir)));
        assertInstanceOf(SqliteNoteRepository.class, Storages.create("   ", json(dir), db(dir)));
    }

    @Test
    @DisplayName("Откат на JSON остаётся доступен одной переменной")
    void rollbackToJsonStillWorks(@TempDir Path dir) {
        assertInstanceOf(JsonNoteRepository.class,
                Storages.create(Storages.JSON, json(dir), db(dir)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"sqlite", "SQLITE", "SqLiTe", "  sqlite  "})
    @DisplayName("Регистр и пробелы в значении не мешают")
    void acceptsSqliteInAnyCase(String kind, @TempDir Path dir) {
        assertInstanceOf(SqliteNoteRepository.class, Storages.create(kind, json(dir), db(dir)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"json", "JSON", " Json "})
    @DisplayName("То же для json")
    void acceptsJsonInAnyCase(String kind, @TempDir Path dir) {
        assertInstanceOf(JsonNoteRepository.class, Storages.create(kind, json(dir), db(dir)));
    }

    @Test
    @DisplayName("Опечатка в значении — громкая ошибка, а не тихий переход на другое хранилище")
    void unknownKindFailsLoudly(@TempDir Path dir) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Storages.create("sqlite3", json(dir), db(dir)));

        assertTrue(e.getMessage().contains("sqlite3"), e.getMessage());
        assertTrue(e.getMessage().contains(Storages.ENV_KIND), "в сообщении должно быть имя переменной");
    }

    @Test
    @DisplayName("Выбор sqlite создаёт файл базы и готовую схему")
    void sqliteIsReadyToUse(@TempDir Path dir) {
        NoteRepository repo = Storages.create(Storages.SQLITE, json(dir), db(dir));

        repo.save(new Note("n1", "надо проверить", NoteType.TASK,
                java.time.Instant.parse("2026-07-27T10:00:00Z"), List.of(), "console", null));

        assertTrue(Files.exists(db(dir)), "файл базы должен появиться");
        assertEquals(1, repo.count());
    }

    @Test
    @DisplayName("Хранилища не путаются: записанное в одно не видно в другом")
    void storagesAreIndependent(@TempDir Path dir) {
        NoteRepository sqlite = Storages.create(Storages.SQLITE, json(dir), db(dir));
        sqlite.save(new Note("n1", "в базе", NoteType.NOTE,
                java.time.Instant.parse("2026-07-27T10:00:00Z"), List.of(), "console", null));

        NoteRepository jsonRepo = Storages.create(Storages.JSON, json(dir), db(dir));

        assertEquals(1, sqlite.count());
        assertEquals(0, jsonRepo.count(), "это разные хранилища — данные не переносятся сами");
    }

    @Test
    @DisplayName("describe() называет конкретный файл, а не просто тип хранилища")
    void describeNamesTheFile(@TempDir Path dir) {
        assertTrue(Storages.create(Storages.JSON, json(dir), db(dir)).describe()
                        .contains("notes.json"),
                "по описанию должно быть понятно, где именно лежат заметки");
        assertTrue(Storages.create(Storages.SQLITE, json(dir), db(dir)).describe()
                        .contains("second-brain.db"));
    }
}
