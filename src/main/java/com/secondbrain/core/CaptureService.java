package com.secondbrain.core;

import com.secondbrain.classify.ClassificationResult;
import com.secondbrain.classify.Classifier;
import com.secondbrain.classify.Tags;
import com.secondbrain.model.Note;
import com.secondbrain.notion.NotionSyncService;
import com.secondbrain.storage.NoteRepository;

import java.util.List;

/**
 * Захват мысли: классифицировать → извлечь теги → сохранить локально → отправить в Notion.
 *
 * <p>Порядок критичен для гарантии «ноль потерь»: заметка сначала попадает
 * в локальное хранилище и только потом уходит в Notion. Если отправка не удалась,
 * заметка остаётся в очереди и уйдёт позже — захват при этом считается успешным.
 */
public class CaptureService {

    private final Classifier classifier;
    private final NoteRepository repository;
    private final NotionSyncService notionSync;

    public CaptureService(Classifier classifier, NoteRepository repository) {
        this(classifier, repository, null);
    }

    public CaptureService(Classifier classifier, NoteRepository repository, NotionSyncService notionSync) {
        this.classifier = classifier;
        this.repository = repository;
        this.notionSync = notionSync;
    }

    /**
     * Захватывает одну мысль.
     *
     * @param text   исходный текст
     * @param source источник ("console", позже "api"/"telegram")
     * @return сохранённая заметка, детали классификации и итог отправки в Notion
     */
    public Captured capture(String text, String source) {
        ClassificationResult result = classifier.classify(text);
        List<String> tags = Tags.extract(text);
        Note note = Note.create(text, result.type(), tags, source);

        // Шаг 1 — локально. С этого момента мысль не потеряется.
        repository.save(note);

        // Шаг 2 — Notion. Неудача не отменяет захват.
        NotionSyncService.SyncResult sync = (notionSync == null)
                ? NotionSyncService.SyncResult.skipped("Notion не подключён")
                : notionSync.trySync(note);

        // Заметка неизменяемая, поэтому объект, созданный до отправки, о ней не знает.
        // Без этой строки ответ сообщал бы «не отправлено» о заметке, которая
        // на самом деле уже в Notion и записана такой в хранилище.
        Note saved = sync.isSent() ? note.withNotionPageId(sync.detail()) : note;

        return new Captured(saved, result, sync);
    }

    /** Заметка + как её классифицировали + что стало с отправкой в Notion. */
    public record Captured(Note note,
                           ClassificationResult classification,
                           NotionSyncService.SyncResult sync) {
    }
}
