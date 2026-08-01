package com.secondbrain.classify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Классификатор с подстраховкой: сначала основной, при любой неудаче — запасной.
 *
 * <p>Существует ради того же принципа, что и очередь досылки в Notion:
 * <b>внешний сервис не должен мешать захвату мысли</b>. Anthropic API может быть
 * недоступен, ключ — просрочен, лимит — исчерпан, ответ — задержаться. Ни один
 * из этих случаев не должен приводить к потере мысли или к зависшей консоли.
 *
 * <p>Поэтому неудача основного классификатора здесь — <b>штатный сценарий</b>,
 * а не ошибка: мысль классифицируется правилами и сохраняется как обычно.
 * Разница видна только в качестве категории, и то не всегда.
 *
 * <p>Причина неудачи попадает в текст объяснения, чтобы было понятно, почему
 * заметка классифицирована проще обычного.
 */
public class FallbackClassifier implements Classifier {

    private static final Logger log = LoggerFactory.getLogger(FallbackClassifier.class);

    private final Classifier primary;
    private final Classifier fallback;

    public FallbackClassifier(Classifier primary, Classifier fallback) {
        this.primary = primary;
        this.fallback = fallback;
    }

    @Override
    public ClassificationResult classify(String text) {
        try {
            return primary.classify(text);
        } catch (RuntimeException e) {
            // Ловим широко намеренно: сюда попадают и сбои сети, и превышение
            // лимитов, и просроченный ключ, и неожиданный формат ответа.
            // Любая из этих причин не должна помешать сохранить мысль.
            log.debug("Умная классификация не удалась, работают правила", e);
            ClassificationResult byRules = fallback.classify(text);
            return ClassificationResult.of(
                    byRules.type(),
                    byRules.confidence(),
                    byRules.reason() + " (по правилам: " + shortReason(e) + ")",
                    byRules.tags());
        }
    }

    private static String shortReason(RuntimeException e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return message.length() <= 80 ? message : message.substring(0, 80) + "…";
    }
}
