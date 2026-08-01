package com.secondbrain.classify;

import com.secondbrain.model.NoteType;

import java.util.List;

/**
 * Результат классификации: категория, уверенность, причина и предложенные теги.
 *
 * @param type        присвоенная категория
 * @param confidence  уверенность в диапазоне [0.0, 1.0]
 * @param reason      короткое объяснение, почему выбрана категория
 * @param tags        теги, предложенные классификатором; для классификатора
 *                    по правилам всегда пусто — там теги берутся из {@code #hashtag}.
 *                    Никогда не {@code null}
 */
public record ClassificationResult(NoteType type,
                                   double confidence,
                                   String reason,
                                   List<String> tags) {

    public ClassificationResult {
        tags = (tags == null) ? List.of() : List.copyOf(tags);
    }

    /** Результат без предложенных тегов — для классификатора по правилам. */
    public static ClassificationResult of(NoteType type, double confidence, String reason) {
        return new ClassificationResult(type, confidence, reason, List.of());
    }

    public static ClassificationResult of(NoteType type, double confidence, String reason,
                                          List<String> tags) {
        return new ClassificationResult(type, confidence, reason, tags);
    }
}
