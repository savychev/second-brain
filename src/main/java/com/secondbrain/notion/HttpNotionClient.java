package com.secondbrain.notion;

import com.secondbrain.classify.Urls;
import com.secondbrain.model.Note;
import com.secondbrain.model.NoteType;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Клиент Notion поверх стандартного HTTP-клиента Java.
 *
 * <p>Внешних библиотек для HTTP не требуется: {@link HttpClient} входит в JDK,
 * JSON собираем через Jackson, который уже используется для хранилища.
 *
 * <p>Работает с версией API {@code 2022-06-28} и создаёт страницу в базе,
 * соответствующей типу заметки.
 */
public class HttpNotionClient implements NotionClient {

    private static final String API_URL = "https://api.notion.com/v1/pages";
    private static final String API_VERSION = "2022-06-28";

    /** Notion не принимает текстовые фрагменты длиннее 2000 символов. */
    private static final int MAX_TEXT_CHUNK = 2000;

    // Названия свойств в базах Notion — должны совпадать со схемой баз.
    private static final String PROP_TITLE = "Мысль";
    private static final String PROP_CAPTURED = "Захвачено";
    private static final String PROP_TAGS = "Теги";
    private static final String PROP_SOURCE = "Источник";
    private static final String PROP_LOCAL_ID = "ID";
    private static final String PROP_URL = "Ссылка";

    private final NotionConfig config;
    private final HttpClient http;
    private final ObjectMapper mapper = JsonMapper.builder().build();

    public HttpNotionClient(NotionConfig config) {
        this(config, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build());
    }

    /** Конструктор для тестов: позволяет подставить свой {@link HttpClient}. */
    public HttpNotionClient(NotionConfig config, HttpClient http) {
        this.config = config;
        this.http = http;
    }

    @Override
    public String createPage(Note note) throws NotionException {
        if (!config.isEnabled()) {
            throw new NotionException("Notion не настроен: " + config.disabledReason());
        }
        String databaseId = config.databaseId(note.type());
        if (databaseId == null) {
            throw new NotionException("Не настроена база Notion для типа " + note.type());
        }

        String body = buildRequestBody(note, databaseId);
        HttpRequest request = HttpRequest.newBuilder(URI.create(API_URL))
                .header("Authorization", "Bearer " + config.token())
                .header("Notion-Version", API_VERSION)
                .header("Content-Type", "application/json; charset=utf-8")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new NotionException("Notion недоступен: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NotionException("Отправка в Notion прервана", e);
        }

        if (response.statusCode() / 100 != 2) {
            throw new NotionException("Notion отверг запрос (HTTP " + response.statusCode()
                    + "): " + shorten(response.body()));
        }

        try {
            JsonNode json = mapper.readTree(response.body());
            JsonNode id = json.get("id");
            if (id == null || id.isNull()) {
                throw new NotionException("Notion не вернул id созданной страницы");
            }
            return id.asText();
        } catch (JacksonException e) {
            // В Jackson 3 ошибки разбора — непроверяемые (наследники RuntimeException),
            // поэтому здесь ловим именно их, а не IOException.
            throw new NotionException("Не удалось разобрать ответ Notion: " + e.getMessage(), e);
        }
    }

    /** Собирает тело запроса на создание страницы. */
    String buildRequestBody(Note note, String databaseId) {
        ObjectNode root = mapper.createObjectNode();
        root.putObject("parent").put("database_id", databaseId);

        ObjectNode props = root.putObject("properties");

        // Заголовок — текст мысли (обрезаем до лимита Notion).
        props.putObject(PROP_TITLE)
                .putArray("title")
                .addObject()
                .putObject("text")
                .put("content", chunk(note.text()));

        // Дата захвата.
        props.putObject(PROP_CAPTURED)
                .putObject("date")
                .put("start", note.createdAt().toString());

        // Источник — select.
        props.putObject(PROP_SOURCE)
                .putObject("select")
                .put("name", note.source());

        // Локальный id — защита от дублей при повторной отправке.
        props.putObject(PROP_LOCAL_ID)
                .putArray("rich_text")
                .addObject()
                .putObject("text")
                .put("content", note.id());

        // Теги — multi_select (Notion создаёт недостающие опции сам).
        if (!note.tags().isEmpty()) {
            ArrayNode tags = props.putObject(PROP_TAGS).putArray("multi_select");
            for (String tag : note.tags()) {
                tags.addObject().put("name", tag);
            }
        }

        // Для ссылок — отдельное поле с адресом.
        if (note.type() == NoteType.LINK) {
            String url = Urls.firstUrl(note.text());
            if (url != null) {
                props.putObject(PROP_URL).put("url", url);
            }
        }

        // Если мысль длиннее лимита заголовка — кладём полный текст в тело страницы.
        if (note.text().length() > MAX_TEXT_CHUNK) {
            ArrayNode children = root.putArray("children");
            for (String part : split(note.text())) {
                ObjectNode paragraph = children.addObject();
                paragraph.put("object", "block");
                paragraph.put("type", "paragraph");
                paragraph.putObject("paragraph")
                        .putArray("rich_text")
                        .addObject()
                        .putObject("text")
                        .put("content", part);
            }
        }

        return root.toString();
    }

    private static String chunk(String text) {
        return text.length() <= MAX_TEXT_CHUNK ? text : text.substring(0, MAX_TEXT_CHUNK);
    }

    private static java.util.List<String> split(String text) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        for (int i = 0; i < text.length(); i += MAX_TEXT_CHUNK) {
            parts.add(text.substring(i, Math.min(text.length(), i + MAX_TEXT_CHUNK)));
        }
        return parts;
    }

    private static String shorten(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 300 ? body : body.substring(0, 300) + "…";
    }
}
