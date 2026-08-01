package com.secondbrain.storage;

import com.secondbrain.model.Note;
import com.secondbrain.model.NoteType;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

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
        // Jackson 3 собирает mapper строителем; поддержка java.time встроена,
        // отдельный модуль jsr310 больше не нужен.
        this.mapper = JsonMapper.builder()
                // Даты пишем как ISO-строки, а не числом — файл должен читаться глазами.
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.INDENT_OUTPUT)
                // Формат хранения будет расти: файл, записанный более новой
                // версией, не должен ронять более старую.
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
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

    @Override
    public String describe() {
        return "JSON-файл " + file.toAbsolutePath().normalize();
    }

    @Override
    public List<Note> findUnsynced() {
        synchronized (lock) {
            List<Note> all = readAll();
            all.sort(Comparator.comparing(Note::createdAt)); // от старых к новым
            return all.stream().filter(n -> !n.isSynced()).toList();
        }
    }

    @Override
    public void markSynced(String noteId, String notionPageId) {
        synchronized (lock) {
            List<Note> all = readAll();
            boolean changed = false;
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).id().equals(noteId)) {
                    all.set(i, all.get(i).withNotionPageId(notionPageId));
                    changed = true;
                    break;
                }
            }
            if (changed) {
                writeAll(all);
            }
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
        } catch (JacksonException e) {
            // В Jackson 3 ошибки разбора непроверяемые — без явного перехвата
            // повреждённый файл дал бы сырое исключение без указания, какой именно файл.
            throw new IllegalStateException(
                    "Хранилище повреждено и не разбирается: " + file
                            + ". Файл не тронут — можно починить вручную или восстановить из копии.", e);
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
