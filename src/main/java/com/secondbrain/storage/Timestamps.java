package com.secondbrain.storage;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;

/**
 * Перевод момента времени в текст постоянной ширины и обратно.
 *
 * <p>Зачем отдельный класс. В SQLite нет типа «дата», время хранится строкой,
 * и сортировка по нему — строковая. А {@link Instant#toString()} обрезает
 * незначащие нули дробной части:
 *
 * <pre>
 *   2026-07-27T18:59:06Z              ← ровно секунда
 *   2026-07-27T18:59:06.305123200Z    ← с наносекундами
 * </pre>
 *
 * <p>При строковом сравнении первая строка оказывается «больше» второй, потому что
 * в позиции 20 у неё символ {@code 'Z'} (0x5A), а у второй — точка (0x2E). То есть
 * заметка, захваченная раньше, встала бы в списке позже. В {@code data/notes.json}
 * уже есть оба варианта записи, так что это не гипотетическая проблема.
 *
 * <p>Решение: всегда писать 9 знаков после точки. Тогда все строки одной длины
 * и строковый порядок совпадает с хронологическим.
 */
public final class Timestamps {

    /** ISO-8601 с фиксированными девятью знаками дробной части. */
    private static final DateTimeFormatter FIXED_WIDTH =
            new DateTimeFormatterBuilder().appendInstant(9).toFormatter();

    private Timestamps() {
    }

    /** Момент времени → строка для хранения в базе. */
    public static String toText(Instant instant) {
        return FIXED_WIDTH.format(instant);
    }

    /** Строка из базы → момент времени. Разбирает и записи переменной ширины. */
    public static Instant parse(String text) {
        return Instant.parse(text);
    }
}
