package com.secondbrain.classify;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Поиск ссылок в тексте мысли.
 *
 * <p>Единое место для правила «что считается ссылкой»: им пользуется и
 * классификатор (чтобы отнести мысль к LINK), и клиент Notion (чтобы заполнить
 * отдельное поле «Ссылка»).
 */
public final class Urls {

    /** http(s)://…, www.… или домен вида example.com/… */
    private static final Pattern URL = Pattern.compile(
            "(https?://\\S+)"
                    + "|(www\\.\\S+)"
                    + "|(\\b[\\p{L}\\d-]+\\.(?:com|ru|org|io|net|dev|me|ai|app|co|info|edu|gov)\\b\\S*)",
            Pattern.CASE_INSENSITIVE);

    private Urls() {
    }

    /** Есть ли в тексте ссылка. */
    public static boolean containsUrl(String text) {
        return text != null && URL.matcher(text).find();
    }

    /**
     * Первая ссылка из текста, приведённая к виду с протоколом
     * (Notion принимает в поле URL только абсолютные адреса).
     *
     * @return ссылка или {@code null}, если её нет
     */
    public static String firstUrl(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = URL.matcher(text);
        if (!m.find()) {
            return null;
        }
        String url = trimTrailingPunctuation(m.group());
        if (url.regionMatches(true, 0, "http", 0, 4)) {
            return url;
        }
        return "https://" + url;
    }

    /** Убирает знаки препинания, случайно прилипшие к концу ссылки. */
    private static String trimTrailingPunctuation(String url) {
        int end = url.length();
        while (end > 0 && ".,;:!?)»\"'".indexOf(url.charAt(end - 1)) >= 0) {
            end--;
        }
        return url.substring(0, end);
    }
}
