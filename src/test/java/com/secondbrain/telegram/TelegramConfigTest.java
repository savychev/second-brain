package com.secondbrain.telegram;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Загрузка настроек бота.
 *
 * <p>Главное здесь — <b>токен не должен попасть в текст ошибки</b>. Он
 * подставляется в адрес запроса, и кривое значение роняло построение адреса
 * сообщением, содержащим весь адрес целиком. Это сообщение печатается в
 * консоль — то есть ровно туда, откуда человек копирует текст в чат с
 * вопросом «почему не работает?».
 */
class TelegramConfigTest {

    /** Заведомо ненастоящий токен: цифры, двоеточие, буквы — нужный вид. */
    private static final String FAKE_TOKEN = "123456789:AAE-notarealtoken";

    @TempDir
    Path dir;

    private TelegramConfig load(String contents) {
        Path file = dir.resolve("telegram.properties");
        try {
            Files.writeString(file, contents, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return TelegramConfig.load(file);
    }

    @Test
    @DisplayName("ГЛАВНОЕ: испорченный токен не попадает в текст ошибки")
    void brokenTokenIsNeverEchoed() {
        // Пробел внутри значения: символ, недопустимый в адресе.
        String broken = "123456789:AAE-not areal token";

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> load("telegram.bot.token=" + broken));

        assertFalse(e.getMessage().contains(broken),
                "токен целиком утёк в сообщение: " + e.getMessage());
        assertFalse(e.getMessage().contains("AAE-not"),
                "утекла узнаваемая часть токена: " + e.getMessage());
        assertTrue(e.getMessage().contains(TelegramConfig.CONFIG_FILE),
                "человек должен понять, какой файл чинить: " + e.getMessage());
    }

    @Test
    @DisplayName("Кавычки вокруг токена снимаются — их оставляет консоль Windows")
    void surroundingQuotesAreStripped() {
        TelegramConfig config = load("telegram.bot.token=\"" + FAKE_TOKEN + "\"");

        assertEquals(FAKE_TOKEN, config.token(),
                "иначе кавычки уехали бы в адрес запроса");
        assertTrue(config.isEnabled());
    }

    @Test
    @DisplayName("Нормальный токен загружается как есть")
    void validTokenLoads() {
        TelegramConfig config = load("telegram.bot.token=" + FAKE_TOKEN);

        assertEquals(FAKE_TOKEN, config.token());
    }

    @Test
    @DisplayName("Без токена бот просто выключен, а не сломан")
    void missingTokenDisablesBot() {
        TelegramConfig config = load("# пусто");

        assertFalse(config.isEnabled());
        assertTrue(config.describe().contains("выключен"), config.describe());
    }

    @Test
    @DisplayName("Разрешённые чаты разбираются, пробелы не мешают")
    void allowedChatsAreParsed() {
        TelegramConfig config = load("telegram.bot.token=" + FAKE_TOKEN + "\n"
                + "telegram.allowed.chats= 111 , 222 ");

        assertTrue(config.isAllowed(111L));
        assertTrue(config.isAllowed(222L));
        assertFalse(config.isAllowed(333L));
    }

    @Test
    @DisplayName("Опечатка в списке чатов слышна, а не проглочена")
    void malformedChatIdThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> load("telegram.allowed.chats=111,абв"),
                "тихо потерянное разрешение — это часы недоумения");
    }
}
