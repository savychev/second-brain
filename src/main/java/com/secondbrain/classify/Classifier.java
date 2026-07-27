package com.secondbrain.classify;

/**
 * Определяет категорию мысли по её тексту.
 *
 * <p>Этап 1 — реализация по правилам ({@link RuleBasedClassifier}).
 * Этап 4 — реализация через Anthropic API; она встанет на место этого интерфейса
 * без изменений в остальном коде.
 */
public interface Classifier {

    /**
     * Классифицирует текст мысли.
     *
     * @param text исходный текст (может быть любой длины)
     * @return категория с уверенностью и причиной; никогда не {@code null}
     */
    ClassificationResult classify(String text);
}
