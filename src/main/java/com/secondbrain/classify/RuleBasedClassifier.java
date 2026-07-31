package com.secondbrain.classify;

import com.secondbrain.model.NoteType;

import java.util.List;

/**
 * Классификатор по правилам (этап 1).
 *
 * <p>Логика приоритетов:
 * <ol>
 *   <li>если в тексте есть явные слова-действия — {@link NoteType#TASK};</li>
 *   <li>иначе если есть маркеры идеи — {@link NoteType#IDEA};</li>
 *   <li>иначе если есть ссылка — {@link NoteType#LINK};</li>
 *   <li>иначе — {@link NoteType#NOTE}.</li>
 * </ol>
 *
 * <p>Задача важнее ссылки намеренно: «отправить ссылку …» — это задача, а не закладка.
 * Голая ссылка без слов-действий уходит в LINK.
 *
 * <p>Маркеры заданы «основами слова» (стемами) и ищутся с начала слова, поэтому
 * «оплат» покрывает «оплатить/оплата», но не срабатывает внутри «неоплаченный».
 * Это грубая эвристика — её слабые места намеренно закрываются умной классификацией
 * через Anthropic API на этапе 4. Точность на тестовом наборе проверяется в
 * {@code RuleBasedClassifierTest} (критерий приёмки P0 №2 — ≥80%).
 */
public class RuleBasedClassifier implements Classifier {

    /** Основы слов-действий — сигнал задачи. */
    private static final List<String> TASK_MARKERS = List.of(
            "todo", "надо", "нужно", "сдел",
            "купи", "позвон", "напис", "напиш",
            "отправ", "записа", "запиш", "заплан",
            "не забыть", "не забудь", "почин",
            "заказ", "закаж", "оплат", "заплат",
            "додел", "законч", "провер", "назнач",
            "договор", "разобра", "убра", "дедлайн"
    );

    /** Основы маркеров идеи. */
    private static final List<String> IDEA_MARKERS = List.of(
            "идея", "идею", "идеи", "идей",
            "а что если", "что если", "что, если", "а вдруг",
            "было бы круто", "было бы здорово",
            "можно сдел", "можно добав", "можно использ", "можно",
            "придум", "концепц"
    );

    @Override
    public ClassificationResult classify(String text) {
        if (text == null || text.isBlank()) {
            return ClassificationResult.of(NoteType.NOTE, 0.3, "пустой текст → NOTE по умолчанию");
        }

        String lower = text.toLowerCase();
        boolean hasUrl = Urls.containsUrl(text);

        String taskHit = firstMatch(lower, TASK_MARKERS);
        if (taskHit != null) {
            return ClassificationResult.of(NoteType.TASK, 0.8,
                    "найдено слово-действие: «" + taskHit + "»");
        }

        String ideaHit = firstMatch(lower, IDEA_MARKERS);
        if (ideaHit != null) {
            return ClassificationResult.of(NoteType.IDEA, 0.75,
                    "найден маркер идеи: «" + ideaHit + "»");
        }

        if (hasUrl) {
            return ClassificationResult.of(NoteType.LINK, 0.85,
                    "в тексте есть ссылка");
        }

        return ClassificationResult.of(NoteType.NOTE, 0.5,
                "нет явных сигналов → NOTE");
    }

    private static String firstMatch(String lowerText, List<String> markers) {
        for (String marker : markers) {
            if (containsAtWordStart(lowerText, marker)) {
                return marker;
            }
        }
        return null;
    }

    /**
     * Проверяет, встречается ли {@code marker} с начала слова (левая граница — не буква).
     * Продолжение слова допускается, поэтому основа «купи» покрывает «купить».
     */
    private static boolean containsAtWordStart(String text, String marker) {
        int from = 0;
        while (true) {
            int idx = text.indexOf(marker, from);
            if (idx < 0) {
                return false;
            }
            boolean leftIsBoundary = idx == 0 || !Character.isLetter(text.charAt(idx - 1));
            if (leftIsBoundary) {
                return true;
            }
            from = idx + 1;
        }
    }
}
