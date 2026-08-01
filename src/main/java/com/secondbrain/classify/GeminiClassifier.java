package com.secondbrain.classify;

import com.secondbrain.model.NoteType;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Классификация мысли моделью Google Gemini.
 *
 * <p>Бесплатный уровень доступа выдаётся по обычному Google-аккаунту, без карты.
 * Как и {@link GroqClassifier}, класс не добавляет зависимостей: HTTP-клиент
 * из JDK и уже подключённый Jackson.
 *
 * <p>Работает через Interactions API — текущий интерфейс Gemini. Он отличается
 * от прежнего {@code generateContent}: другой адрес, другое тело запроса и,
 * главное, другая форма ответа — текст лежит внутри массива {@code steps},
 * а не в привычном {@code candidates}.
 *
 * <p>Форма ответа задаётся схемой, но подстраховка есть и на уровне промпта:
 * системная инструкция тоже требует вернуть объект. Если схема вдруг не
 * применится, разбор всё равно скорее всего удастся, а при неудаче
 * {@link FallbackClassifier} передаст мысль правилам.
 */
public class GeminiClassifier implements Classifier {

    private static final String API_URL =
            "https://generativelanguage.googleapis.com/v1beta/interactions";
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private static final String SCHEMA_NAME = "note_classification";

    private final ProviderConfig config;
    private final HttpClient http;

    public GeminiClassifier(ProviderConfig config) {
        this(config, HttpClient.newBuilder().connectTimeout(config.timeout()).build());
    }

    /** Конструктор для тестов: позволяет подставить свой HTTP-клиент. */
    public GeminiClassifier(ProviderConfig config, HttpClient http) {
        this.config = config;
        this.http = http;
    }

    @Override
    public ClassificationResult classify(String text) {
        if (text == null || text.isBlank()) {
            return ClassificationResult.of(NoteType.NOTE, 0.3, "пустой текст → NOTE по умолчанию");
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(API_URL))
                // Ключ идёт заголовком, а не параметром адреса: параметры
                // попадают в журналы прокси и истории, заголовки — нет.
                .header("x-goog-api-key", config.apiKey())
                .header("Content-Type", "application/json; charset=utf-8")
                .timeout(config.timeout())
                .POST(HttpRequest.BodyPublishers.ofString(body(text), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ClassificationFailedException("Gemini недоступен: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ClassificationFailedException("запрос к Gemini прерван", e);
        }

        if (response.statusCode() / 100 != 2) {
            throw new ClassificationFailedException(
                    "Gemini отверг запрос (HTTP " + response.statusCode() + "): "
                            + shorten(response.body()));
        }
        return parse(extractText(response.body()));
    }

    private String body(String text) {
        ObjectNode root = JSON.createObjectNode();
        root.put("model", config.model());
        root.put("input", text);
        root.put("system_instruction", ClassificationPrompt.SYSTEM);

        ObjectNode generation = root.putObject("generation_config");
        generation.put("max_output_tokens", 500);
        // Классификация — не задача на размышление: просим минимум,
        // это заметно быстрее и дешевле по лимитам.
        generation.put("thinking_level", "minimal");

        ObjectNode format = root.putObject("response_format");
        format.put("type", "json_schema");
        ObjectNode jsonSchema = format.putObject("json_schema");
        jsonSchema.put("name", SCHEMA_NAME);
        jsonSchema.set("schema", ClassificationPrompt.jsonSchema(JSON));

        return root.toString();
    }

    /**
     * Достаёт текст ответа.
     *
     * <p>У Interactions API ответ разбит на шаги, и нужный лежит в шаге
     * {@code model_output}. Ищем терпимо — по всем шагам и всем блокам, —
     * потому что состав шагов зависит от того, что модель делала по пути.
     */
    private static String extractText(String responseBody) {
        JsonNode root;
        try {
            root = JSON.readTree(responseBody);
        } catch (JacksonException e) {
            throw new ClassificationFailedException(
                    "ответ Gemini не разбирается: " + e.getMessage(), e);
        }

        for (JsonNode step : root.path("steps")) {
            for (JsonNode block : step.path("content")) {
                String value = block.path("text").asString("");
                if (!value.isBlank()) {
                    return value;
                }
            }
        }
        throw new ClassificationFailedException(
                "в ответе Gemini нет текста: " + shorten(responseBody));
    }

    private static ClassificationResult parse(String json) {
        JsonNode node;
        try {
            node = JSON.readTree(json.trim());
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
