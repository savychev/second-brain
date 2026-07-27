package com.secondbrain.classify;

import com.secondbrain.model.NoteType;

/**
 * Результат классификации: категория, уверенность и человекочитаемая причина.
 *
 * @param type       присвоенная категория
 * @param confidence уверенность в диапазоне [0.0, 1.0]
 * @param reason     короткое объяснение, почему выбрана категория (для отладки/логов)
 */
public record ClassificationResult(NoteType type, double confidence, String reason) {

    public static ClassificationResult of(NoteType type, double confidence, String reason) {
        return new ClassificationResult(type, confidence, reason);
    }
}
