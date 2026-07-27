package com.secondbrain.classify;

import com.secondbrain.model.NoteType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleBasedClassifierTest {

    private final RuleBasedClassifier classifier = new RuleBasedClassifier();

    /**
     * Тестовый набор из ~30 реальных мыслей (критерий приёмки P0 №2).
     * Порядок: текст → ожидаемый тип.
     */
    private record Case(String text, NoteType expected) {
    }

    private static final List<Case> DATASET = List.of(
            // TASK
            new Case("купить молоко и хлеб", NoteType.TASK),
            new Case("позвонить маме вечером", NoteType.TASK),
            new Case("надо записаться к врачу", NoteType.TASK),
            new Case("отправить отчёт до пятницы", NoteType.TASK),
            new Case("не забыть оплатить интернет", NoteType.TASK),
            new Case("todo: доделать презентацию", NoteType.TASK),
            new Case("запланировать встречу с командой", NoteType.TASK),
            new Case("починить кран на кухне", NoteType.TASK),
            new Case("заказать билеты на самолёт", NoteType.TASK),
            new Case("написать письмо клиенту завтра", NoteType.TASK),

            // IDEA
            new Case("идея: приложение для захвата мыслей", NoteType.IDEA),
            new Case("а что если запускать дайджест по выходным", NoteType.IDEA),
            new Case("было бы круто автоматизировать отчёты", NoteType.IDEA),
            new Case("можно добавить голосовой ввод в бота", NoteType.IDEA),
            new Case("придумал название для проекта — SecondBrain", NoteType.IDEA),
            new Case("что если классификатор будет учиться на правках", NoteType.IDEA),
            new Case("концепция: единая точка входа для всех заметок", NoteType.IDEA),

            // LINK
            new Case("https://spring.io/guides", NoteType.LINK),
            new Case("интересная статья https://habr.com/ru/articles/123", NoteType.LINK),
            new Case("www.notion.so/api", NoteType.LINK),
            new Case("https://github.com/anthropics/anthropic-sdk-java позже", NoteType.LINK),
            new Case("youtube.com/watch?v=abc обучение по Spring Boot", NoteType.LINK),

            // NOTE
            new Case("сегодня был продуктивный день", NoteType.NOTE),
            new Case("встреча прошла хорошо, обсудили условия", NoteType.NOTE),
            new Case("мне нравится как звучит эта музыка", NoteType.NOTE),
            new Case("погода отличная, солнечно", NoteType.NOTE),
            new Case("интересная мысль про свободное время", NoteType.NOTE),
            new Case("чувствую усталость после недели", NoteType.NOTE),
            new Case("хорошая книга, советую прочитать", NoteType.NOTE),
            new Case("разговор с другом получился тёплым", NoteType.NOTE)
    );

    @Test
    @DisplayName("Точность на тестовом наборе ≥ 80% (критерий приёмки P0 №2)")
    void accuracyAtLeast80Percent() {
        int correct = 0;
        StringBuilder misses = new StringBuilder();
        for (Case c : DATASET) {
            NoteType actual = classifier.classify(c.text()).type();
            if (actual == c.expected()) {
                correct++;
            } else {
                misses.append(String.format("%n  «%s» → ожидали %s, получили %s",
                        c.text(), c.expected(), actual));
            }
        }
        int correctFinal = correct;
        double accuracy = (double) correct / DATASET.size();
        assertTrue(accuracy >= 0.80,
                () -> String.format("Точность %.1f%% (%d/%d) < 80%%. Ошибки:%s",
                        accuracy * 100, correctFinal, DATASET.size(), misses));
    }

    @ParameterizedTest
    @CsvSource(textBlock = """
            купить хлеб,                       TASK
            надо позвонить в банк,             TASK
            идея: тёмная тема для приложения,  IDEA
            https://example.com,               LINK
            www.google.com,                    LINK
            просто наблюдение о жизни,          NOTE
            """)
    @DisplayName("Точечные проверки по каждому типу")
    void spotChecks(String text, NoteType expected) {
        assertEquals(expected, classifier.classify(text).type(), text);
    }

    @Test
    @DisplayName("Пустой и пробельный текст → NOTE, без исключений")
    void blankIsNote() {
        assertEquals(NoteType.NOTE, classifier.classify("").type());
        assertEquals(NoteType.NOTE, classifier.classify("   ").type());
        assertEquals(NoteType.NOTE, classifier.classify(null).type());
    }

    @Test
    @DisplayName("Задача важнее ссылки: действие + URL → TASK")
    void taskBeatsLink() {
        assertEquals(NoteType.TASK,
                classifier.classify("отправить ссылку https://example.com коллеге").type());
    }
}
