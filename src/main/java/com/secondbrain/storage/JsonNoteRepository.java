package com.secondbrain.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.secondbrain.model.Note;
import com.secondbrain.model.NoteType;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Хранилище заметок в одном JSON-файле.
 *
 * <p>Простая и надёжная реализация для этапа 1: весь список читается в память,
 * при сохранении переписывается целиком. Для личного объёма заметок этого
 * достаточно; на этапе 3 хранилище заменяется на SQLite.
 *
 * <p>Запись атомарна: пишем во временный файл и переименовываем поверх, чтобы
 * сбой посреди записи не повредил уже сохранённые данные.
 *
 * <p>Класс потокобезопасен на уровне простого мьютекса — достаточно для CLI и
 * будущего однопроцессного REST API.
 */
public class JsonNoteRepository implements NoteRepository {

    private final Path file;
    private final ObjectMapper mapper;
    private final Object lock = new Object();

    public JsonNoteRepository(Path file) {
        this.file = file;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    @Override
    public void save(Note note) {
        synchronized (lock) {
            List<Note> all = readAll();
            all.add(note);
            writeAll(all);
        }
    }

    @Override
    public List<Note> findAll() {
        synchronized (lock) {
            List<Note> all = readAll();
            all.sort(Comparator.comparing(Note::createdAt).reversed());
            return all;
        }
    }

    @Override
    public List<Note> findByType(NoteType type) {
        return findAll().stream().filter(n -> n.type() == type).toList();
    }

    @Override
    public long count() {
        synchronized (lock) {
            return readAll().size();
        }
    }

    // --- внутреннее ---

    private List<Note> readAll() {
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length == 0) {
                return new ArrayList<>();
            }
            Note[] arr = mapper.readValue(bytes, Note[].class);
            List<Note> list = new ArrayList<>(arr.length);
            for (Note n : arr) {
                list.add(n);
            }
            return list;
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось прочитать хранилище: " + file, e);
        }
    }

    private void writeAll(List<Note> notes) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = Files.createTempFile(parent, "notes-", ".json.tmp");
            mapper.writeValue(tmp.toFile(), notes);
            try {
                Files.move(tmp, file,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                // Некоторые файловые системы Windows не гарантируют ATOMIC_MOVE — деградируем.
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось записать хранилище: " + file, e);
        }
    }
}
