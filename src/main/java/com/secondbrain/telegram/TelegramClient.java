package com.secondbrain.telegram;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Обращения к Telegram Bot API.
 *
 * <p>Зависимостей не добавляет: HTTP-клиент из JDK и уже подключённый Jackson —
 * как во всех внешних интеграциях этого проекта.
 *
 * <p>Работает <b>длинными опросами</b>, а не webhook. Webhook требует публичного
 * адреса с сертификатом, то есть хостинга или туннеля. Опрос работает из-за
 * любого роутера: программа сама спрашивает Telegram «есть новое?», и запрос
 * висит открытым до появления сообщения. Для инструмента, живущего на личном
 * компьютере, это единственный практичный вариант.
 */
public class TelegramClient {

    private static final String API_BASE = "https://api.telegram.org/bot";
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    /**
     * Запас поверх времени ожидания Telegram. Запрос висит открытым столько,
     * сколько попросили; клиент обязан ждать дольше, иначе он оборвёт
     * собственный нормально работающий опрос.
     */
    private static final Duration TIMEOUT_MARGIN = Duration.ofSeconds(15);

    private final TelegramConfig config;
    private final HttpClient http;

    public TelegramClient(TelegramConfig config) {
        this(config, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build());
    }

    /** Конструктор для тестов: позволяет подставить свой HTTP-клиент. */
    public TelegramClient(TelegramConfig config, HttpClient http) {
        this.config = config;
        this.http = http;
    }

    /**
     * Забирает новые сообщения, ожидая до {@code pollTimeout}.
     *
     * @param offset идентификатор первого нужного обновления: подтверждает
     *               Telegram, что предыдущие обработаны, и они больше не придут
     * @return полученные сообщения, возможно пустой список
     */
    public List<TelegramMessage> getUpdates(long offset) {
        ObjectNode request = JSON.createObjectNode();
        request.put("offset", offset);
        request.put("timeout", config.pollTimeout().toSeconds());
        // Просим только текстовые сообщения: остальные типы обновлений
        // этому боту не нужны и только заняли бы канал.
        request.putArray("allowed_updates").add("message");

        JsonNode result = call("getUpdates", request,
                config.pollTimeout().plus(TIMEOUT_MARGIN));

        List<TelegramMessage> messages = new ArrayList<>();
        for (JsonNode update : result) {
            long updateId = update.path("update_id").asLong();
            JsonNode message = update.path("message");
            String text = message.path("text").asString("");
            long chatId = message.path("chat").path("id").asLong();
            String from = message.path("from").path("first_name").asString("");
            if (chatId != 0) {
                messages.add(new TelegramMessage(updateId, chatId, text, from));
            }
        }
        return messages;
    }

    /** Отправляет текст в чат. */
    public void sendMessage(long chatId, String text) {
        ObjectNode request = JSON.createObjectNode();
        request.put("chat_id", chatId);
        request.put("text", text);
        call("sendMessage", request, Duration.ofSeconds(15));
    }

    private JsonNode call(String method, ObjectNode body, Duration timeout) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(API_BASE + config.token() + "/" + method))
                .header("Content-Type", "application/json; charset=utf-8")
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new TelegramException("Telegram недоступен: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TelegramException("запрос к Telegram прерван", e);
        }

        if (response.statusCode() / 100 != 2) {
            // Токен в сообщение НЕ попадает: он в адресе, а мы печатаем только тело.
            throw new TelegramException("Telegram отверг запрос (HTTP "
                    + response.statusCode() + "): " + shorten(response.body()));
        }

        try {
            JsonNode root = JSON.readTree(response.body());
            if (!root.path("ok").asBoolean(false)) {
                throw new TelegramException("Telegram вернул отказ: "
                        + root.path("description").asString("без объяснения"));
            }
            return root.path("result");
        } catch (JacksonException e) {
            throw new TelegramException("ответ Telegram не разбирается: " + e.getMessage(), e);
        }
    }

    private static String shorten(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 300 ? body : body.substring(0, 300) + "…";
    }

    /**
     * Полученное сообщение.
     *
     * @param updateId идентификатор обновления — им подтверждается обработка
     * @param chatId   чат, откуда пришло: по нему проверяется разрешение
     * @param text     текст сообщения
     * @param from     имя отправителя, только для сообщений в журнал
     */
    public record TelegramMessage(long updateId, long chatId, String text, String from) {
    }
}
