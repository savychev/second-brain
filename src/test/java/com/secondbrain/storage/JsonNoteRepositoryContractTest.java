package com.secondbrain.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/**
 * JSON-хранилище прогоняется через тот же контракт, что и SQLite.
 *
 * <p>Это и есть страховка перед переключением на шаге 5: если обе реализации
 * проходят один набор проверок, подмена хранилища не изменит поведение приложения.
 * Тесты, специфичные для JSON, остаются в {@code JsonNoteRepositoryTest}.
 */
class JsonNoteRepositoryContractTest extends NoteRepositoryContractTest {

    @TempDir
    Path dir;

    private NoteRepository repository;

    @BeforeEach
    void setUp() {
        repository = new JsonNoteRepository(dir.resolve("notes.json"));
    }

    @Override
    protected NoteRepository repository() {
        return repository;
    }
}
