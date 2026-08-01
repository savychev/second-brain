package com.secondbrain.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Проверяет главное свойство формата времени: строковый порядок обязан совпадать
 * с хронологическим, иначе лента заметок перемешается.
 */
class TimestampsTest {

    @Test
    @DisplayName("Все строки времени одной длины независимо от точности")
    void alwaysSameWidth() {
        List<String> texts = List.of(
                Timestamps.toText(Instant.parse("2026-07-27T18:59:06Z")),
                Timestamps.toText(Instant.parse("2026-07-27T18:59:06.3Z")),
                Timestamps.toText(Instant.parse("2026-07-27T18:59:06.305123200Z")),
                Timestamps.toText(Instant.EPOCH));

        int width = texts.get(0).length();
        for (String t : texts) {
            assertEquals(width, t.length(), "разная ширина ломает строковую сортировку: " + t);
        }
    }

    @Test
    @DisplayName("Именно тот случай, который ломал бы порядок")
    void theOrderingTrap() {
        // Instant.toString() дал бы "…06Z" и "…06.3Z";
        // при строковом сравнении 'Z'(0x5A) > '.'(0x2E), и более ранний момент
        // оказался бы «больше» более позднего.
        Instant earlier = Instant.parse("2026-07-27T18:59:06Z");
        Instant later = Instant.parse("2026-07-27T18:59:06.3Z");

        assertTrue(earlier.toString().compareTo(later.toString()) > 0,
                "это и есть ловушка: у Instant.toString() порядок неверный");
        assertTrue(Timestamps.toText(earlier).compareTo(Timestamps.toText(later)) < 0,
                "а у Timestamps — верный");
    }

    @Test
    @DisplayName("Строковая сортировка совпадает с хронологической")
    void stringOrderMatchesChronological() {
        List<Instant> chronological = List.of(
                Instant.parse("2026-07-27T18:59:06Z"),
                Instant.parse("2026-07-27T18:59:06.000000001Z"),
                Instant.parse("2026-07-27T18:59:06.3Z"),
                Instant.parse("2026-07-27T18:59:06.305123200Z"),
                Instant.parse("2026-07-27T18:59:07Z"));

        List<String> texts = new ArrayList<>(chronological.stream().map(Timestamps::toText).toList());
        List<String> sorted = new ArrayList<>(texts);
        sorted.sort(String::compareTo);

        assertEquals(texts, sorted);
    }

    @Test
    @DisplayName("Запись и чтение не теряют наносекунды")
    void roundTripKeepsNanos() {
        Instant original = Instant.parse("2026-07-27T18:59:06.305123200Z");

        assertEquals(original, Timestamps.parse(Timestamps.toText(original)));
    }

    @Test
    @DisplayName("Читаются и записи переменной ширины — из файлов этапа 1")
    void parsesLegacyVariableWidth() {
        assertEquals(Instant.parse("2026-07-27T18:59:06Z"),
                Timestamps.parse("2026-07-27T18:59:06Z"));
        assertEquals(Instant.parse("2026-07-27T18:24:14.811680200Z"),
                Timestamps.parse("2026-07-27T18:24:14.811680200Z"));
    }
}
