package com.secondbrain.classify;

import com.secondbrain.model.NoteType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Подстраховка классификации.
 *
 * <p>Проверяет главное свойство этапа 4: <b>внешний сервис не может помешать
 * захвату мысли</b>. Что бы ни случилось с Anthropic API — недоступность, лимит,
 * просроченный ключ, мусор в ответе — мысль классифицируется правилами
 * и сохраняется как обычно.
 */
class FallbackClassifierTest {

    private static final Classifier RULES = new RuleBasedClassifier();

    /** Классификатор, который всегда падает указанной причиной. */
    private static Classifier broken(RuntimeException failure) {
        return text -> {
            throw failure;
        };
    }

    @Test
    @DisplayName("Основной работает — его ответ и возвращается")
    void usesPrimaryWhenItWorks() {
        Classifier smart = text -> ClassificationResult.of(
                NoteType.IDEA, 0.95, "модель поняла смысл", List.of("проект"));
        Classifier classifier = new FallbackClassifier(smart, RULES);

        ClassificationResult result = classifier.classify("заменить лампочку");

        assertEquals(NoteType.IDEA, result.type());
        assertEquals(0.95, result.confidence());
        assertEquals(List.of("проект"), result.tags());
    }

    @Test
    @DisplayName("Сеть недоступна → работают правила, мысль не теряется")
    void fallsBackOnNetworkFailure() {
        Classifier classifier = new FallbackClassifier(
                broken(new RuntimeException("Connection refused")), RULES);

        ClassificationResult result = classifier.classify("надо купить хлеб");

        assertEquals(NoteType.TASK, result.type(), "правила обязаны отработать");
        assertTrue(result.reason().contains("Connection refused"),
                "причина обязана быть видна, чтобы понять, почему классификация проще обычного");
    }

    @Test
    @DisplayName("Отказ модели → тоже штатно, работают правила")
    void fallsBackOnRefusal() {
        Classifier classifier = new FallbackClassifier(
                broken(new ClassificationFailedException("модель отклонила запрос")), RULES);

        ClassificationResult result = classifier.classify("идея: тёмная тема");

        assertEquals(NoteType.IDEA, result.type());
    }

    @Test
    @DisplayName("Мусор в ответе → правила, а не исключение наружу")
    void fallsBackOnGarbageResponse() {
        Classifier classifier = new FallbackClassifier(
                broken(new ClassificationFailedException("ответ модели не разбирается")), RULES);

        ClassificationResult result = classifier.classify("https://spring.io/guides");

        assertEquals(NoteType.LINK, result.type());
    }

    @Test
    @DisplayName("Исключение НИКОГДА не выходит наружу — иначе консоль упадёт на захвате")
    void neverThrows() {
        List<RuntimeException> failures = List.of(
                new RuntimeException("что угодно"),
                new IllegalStateException("лимит запросов исчерпан"),
                new ClassificationFailedException("ключ просрочен"),
                new NullPointerException());

        for (RuntimeException failure : failures) {
            Classifier classifier = new FallbackClassifier(broken(failure), RULES);
            ClassificationResult result = classifier.classify("мысль");
            assertEquals(NoteType.NOTE, result.type(), "для " + failure.getClass().getSimpleName());
        }
    }

    @Test
    @DisplayName("Даже если падает и запасной — не наш случай, но проверим границу")
    void primaryFailureWithWorkingFallbackKeepsTags() {
        Classifier classifier = new FallbackClassifier(
                broken(new RuntimeException("сбой")), RULES);

        ClassificationResult result = classifier.classify("надо купить хлеб #дом");

        // Правила тегов не предлагают — их извлекает Tags из текста.
        assertEquals(List.of(), result.tags());
        assertEquals(NoteType.TASK, result.type());
    }
}
