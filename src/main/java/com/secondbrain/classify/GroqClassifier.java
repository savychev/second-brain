package com.secondbrain.classify;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Классификация мысли моделью через Groq.
 *
 * <p>Groq предоставляет бесплатный уровень доступа к открытым моделям и
 * OpenAI-совместимый интерфейс. Из-за совместимости класс не требует ни одной
 * новой зависимости: используется HTTP-клиент из JDK и уже подключённый Jackson —
 * ровно как в {@code HttpNotionClient}.
 *
 * <p>Ответ ограничен схемой в <b>строгом режиме</b>: модель не «постарается»
 * соблюсти форму, а физически не сможет вернуть другую. Разбирать свободный
 * текст и угадывать смысл не нужно.
 *
 * <p>Как и у любого внешнего сервиса, отказ здесь — штатный случай:
 * {@link FallbackClassifier} перехватит его и передаст мысль правилам.
 */
public class GroqClassifier implements Classifier {

    private static final String API_URL = "https://api.groq.com/openai/v1/chat/completions";
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    /** Имя схемы — требование формата, видно только в запросе. */
    private static final String SCHEMA_NAME = "note_classification";

    private final ProviderConfig config;
    private final HttpClient http;

    public GroqClassifier(ProviderConfig config) {
        this(config, HttpClient.newBuilder().connectTimeout(config.timeout()).build());
    }

    /** Конструктор для тестов: позволяет подставить свой HTTP-клиент. */
    public GroqClassifier(ProviderConfig config, HttpClient http) {
        this.config = config;
        this.http = http;
    }

    @Override
    public ClassificationResult classify(String text) {
        if (text == null || text.isBlank()) {
            return ClassificationResult.of(NoteType.NOTE, 0.3, "пустой текст → NOTE по умолчанию");
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(API_URL))
                .header("Authorization", "Bearer " + config.apiKey())
                .header("Content-Type", "application/json; charset=utf-8")
                .timeout(config.timeout())
                .POST(HttpRequest.BodyPublishers.ofString(body(text), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ClassificationFailedException("Groq недоступен: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ClassificationFailedException("запрос к Groq прерван", e);
        }

        if (response.statusCode() / 100 != 2) {
            throw new ClassificationFailedException(
                    "Groq отверг запрос (HTTP " + response.statusCode() + "): "
                            + shorten(response.body()));
        }
        return parse(extractContent(response.body()));
    }

    /** Тело запроса в формате, совместимом с OpenAI. */
    private String body(String text) {
        ObjectNode root = JSON.createObjectNode();
        root.put("model", config.model());
        // Классификация — не творческая задача: просим наиболее вероятный ответ.
        root.put("temperature", 0);
        root.put("max_tokens", 500);

        ArrayNode messages = root.putArray("messages");
        messages.addObject().put("role", "system").put("content", ClassificationPrompt.SYSTEM);
        messages.addObject().put("role", "user").put("content", text);

        // Строгий режим: модель физически не сможет вернуть другую форму.
        ObjectNode schema = root.putObject("response_format")
                .put("type", "json_schema")
                .putObject("json_schema");
        schema.put("name", SCHEMA_NAME);
        schema.put("strict", true);
        schema.set("schema", ClassificationPrompt.jsonSchema(JSON));

        return root.toString();
    }

    /** Достаёт текст ответа из OpenAI-совместимой обёртки. */
    private static String extractContent(String responseBody) {
        try {
            JsonNode root = JSON.readTree(responseBody);
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.asString("").isBlank()) {
                throw new ClassificationFailedException("ответ Groq пуст");
            }
            return content.asString();
        } catch (JacksonException e) {
            throw new ClassificationFailedException(
                    "ответ Groq не разбирается: " + e.getMessage(), e);
        }
    }

    private static ClassificationResult parse(String json) {
        JsonNode node;
        try {
            node = JSON.readTree(json);
        } catch (JacksonException e) {
            throw new ClassificationFailedException(
                    "содержимое ответа не разбирается: " + e.getMessage(), e);
        }

        NoteType type = parseType(node.path("type").asString(""));
        double confidence = Math.clamp(node.path("confidence").asDouble(0.5), 0.0, 1.0);
        String reason = node.path("reason").asString("определено моделью");

        List<String> tags = new ArrayList<>();
        JsonNode tagsNode = node.path("tags");
        if (tagsNode.isArray()) {
            for (JsonNode tag : tagsNode) {
                String value = tag.asString("").trim().toLowerCase(Locale.ROOT);
                if (!value.isEmpty() && !tags.contains(value) && tags.size() < 3) {
                    tags.add(value);
                }
            }
        }
        return ClassificationResult.of(type, confidence, reason, tags);
    }

    private static NoteType parseType(String value) {
        try {
            return NoteType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            // Строгий режим схемы такого не допускает, но полагаться на это
            // без проверки нельзя: ответ приходит из-за пределов программы.
            throw new ClassificationFailedException("модель вернула неизвестный тип: «" + value + "»");
        }
    }

    private static String shorten(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 300 ? body : body.substring(0, 300) + "…";
    }
}
