package com.secondbrain.telegram;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Разбор ответа Telegram.
 *
 * <p>Главное здесь — <b>указатель должен двигаться по каждому обновлению</b>,
 * включая те, из которых сообщения не вышло. Иначе такое обновление никогда не
 * подтверждается, Telegram отдаёт его снова и снова, и опрос крутится вхолостую
 * до тех пор, пока владелец случайно не напишет боту.
 */
class TelegramClientTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static JsonNode result(String json) {
        return JSON.readTree(json);
    }

    @Test
    @DisplayName("ГЛАВНОЕ: обновление без сообщения всё равно двигает указатель")
    void updateWithoutMessageStillAdvancesOffset() {
        // Так выглядит my_chat_member — «бота добавили в группу».
        // Поля message в нём нет вовсе.
        JsonNode updates = result("""
                [{"update_id": 700, "my_chat_member": {"chat": {"id": -1001}}}]""");

        TelegramClient.Updates parsed = TelegramClient.parseUpdates(updates, 0);

        assertTrue(parsed.messages().isEmpty(), "сообщения тут нет, и это верно");
        assertEquals(701, parsed.nextOffset(),
                "без сдвига Telegram отдавал бы это обновление бесконечно");
    }

    @Test
    @DisplayName("Указатель встаёт за самым большим обновлением пачки")
    void offsetCoversWholeBatch() {
        JsonNode updates = result("""
                [{"update_id": 10, "message": {"chat": {"id": 111}, "text": "раз"}},
                 {"update_id": 11, "edited_message": {"chat": {"id": 111}}},
                 {"update_id": 12, "message": {"chat": {"id": 111}, "text": "два"}}]""");

        TelegramClient.Updates parsed = TelegramClient.parseUpdates(updates, 0);

        assertEquals(2, parsed.messages().size());
        assertEquals(13, parsed.nextOffset());
    }

    @Test
    @DisplayName("Пустой ответ оставляет указатель на месте")
    void emptyResultKeepsOffset() {
        TelegramClient.Updates parsed = TelegramClient.parseUpdates(result("[]"), 42);

        assertTrue(parsed.messages().isEmpty());
        assertEquals(42, parsed.nextOffset());
    }

    @Test
    @DisplayName("Указатель не едет назад")
    void offsetNeverGoesBackwards() {
        JsonNode updates = result("""
                [{"update_id": 5, "message": {"chat": {"id": 111}, "text": "старое"}}]""");

        TelegramClient.Updates parsed = TelegramClient.parseUpdates(updates, 100);

        assertEquals(100, parsed.nextOffset());
    }

    @Test
    @DisplayName("Сообщение разбирается целиком: чат, текст, отправитель")
    void messageIsParsed() {
        JsonNode updates = result("""
                [{"update_id": 1, "message": {
                    "chat": {"id": 555},
                    "text": "мысль",
                    "from": {"first_name": "Имя"}}}]""");

        TelegramClient.TelegramMessage message =
                TelegramClient.parseUpdates(updates, 0).messages().get(0);

        assertEquals(555, message.chatId());
        assertEquals("мысль", message.text());
        assertEquals("Имя", message.from());
    }
}
