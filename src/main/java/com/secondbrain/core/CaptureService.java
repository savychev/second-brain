package com.secondbrain.core;

import com.secondbrain.classify.ClassificationResult;
import com.secondbrain.classify.Classifier;
import com.secondbrain.classify.Tags;
import com.secondbrain.model.Note;
import com.secondbrain.storage.NoteRepository;

import java.util.List;

/**
 * Захват мысли: классифицировать → извлечь теги → сохранить.
 *
 * <p>Единственная точка, где мысль превращается в сохранённую заметку.
 * Порядок важен для гарантии «ноль потерь»: заметка попадает в локальное
 * хранилище всегда, отправка в Notion (этап 2) — уже поверх сохранённого.
 */
public class CaptureService {

    private final Classifier classifier;
    private final NoteRepository repository;

    public CaptureService(Classifier classifier, NoteRepository repository) {
        this.classifier = classifier;
        this.repository = repository;
    }

    /**
     * Захватывает одну мысль.
     *
     * @param text   исходный текст
     * @param source источник ("console", позже "api"/"telegram")
     * @return сохранённая заметка вместе с деталями классификации
     */
    public Captured capture(String text, String source) {
        ClassificationResult result = classifier.classify(text);
        List<String> tags = Tags.extract(text);
        Note note = Note.create(text, result.type(), tags, source);
        repository.save(note);
        return new Captured(note, result);
    }

    /** Заметка + как её классифицировали (причина/уверенность). */
    public record Captured(Note note, ClassificationResult classification) {
    }
}
