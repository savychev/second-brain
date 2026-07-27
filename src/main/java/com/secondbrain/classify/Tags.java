package com.secondbrain.classify;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Извлечение тегов из текста мысли.
 *
 * <p>Этап 1: простые {@code #hashtag}-теги. Этап 4: сюда добавится извлечение
 * смысловых тегов через Anthropic API.
 */
public final class Tags {

    // #слово: буквы (в т.ч. кириллица), цифры и подчёркивание, минимум один символ.
    private static final Pattern HASHTAG = Pattern.compile("#([\\p{L}\\p{N}_]+)");

    private Tags() {
    }

    /**
     * Возвращает список уникальных тегов (в нижнем регистре, без «#»),
     * сохраняя порядок появления. Пустой список, если тегов нет.
     */
    public static List<String> extract(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Set<String> found = new LinkedHashSet<>();
        Matcher m = HASHTAG.matcher(text);
        while (m.find()) {
            found.add(m.group(1).toLowerCase());
        }
        return new ArrayList<>(found);
    }
}
