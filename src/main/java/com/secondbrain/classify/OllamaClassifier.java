package com.secondbrain.classify;

import com.secondbrain.model.NoteType;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Классификация мысли моделью, работающей на этом же компьютере, через Ollama.
 *
 * <p>Единственный вариант без аккаунта, ключа, лимитов и оплаты. И единственный,
 * при котором <b>мысли не покидают компьютер</b> — для системы, куда сбрасывается
 * всё подряд, это не мелочь.
 *
 * <p>Цена — скорость: на процессоре ответ занимает секунды, а не доли секунды.
 * Для выбора одной категории из четырёх этого достаточно, и в цель «захват
 * за 10 секунд» укладывается. Если не уложится, {@link FallbackClassifier}
 * передаст мысль правилам по таймауту.
 *
 * <p>Ollama выбирается только явно ({@code classifier.provider=ollama}), а не
 * автоматически: иначе приложение на каждом запуске пыталось бы достучаться
 * до несуществующей службы и ждало отказа.
 */
public class OllamaClassifier implements Classifier {

    /** Адрес по умолчанию — Ollama слушает локально на этом порту. */
    public static final String DEFAULT_HOST = "http://localhost:11434";

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    /** Предел ожидания прогрева: загрузка модели с диска — дело небыстрое. */
    private static final Duration WARMUP_TIMEOUT = Duration.ofMinutes(3);

    private final ProviderConfig config;
    private final String endpoint;
    private final HttpClient http;

    public OllamaClassifier(ProviderConfig config) {
        this(config, HttpClient.newBuilder().connectTimeout(config.timeout()).build());
    }

    /** Конструктор для тестов: позволяет подставить свой HTTP-клиент. */
    public OllamaClassifier(ProviderConfig config, HttpClient http) {
        this.config = config;
        this.http = http;
        String host = (config.host() == null || config.host().isBlank())
                ? DEFAULT_HOST
                : config.host().trim();
        this.endpoint = host.replaceAll("/+$", "") + "/api/chat";
    }

    @Override
    public ClassificationResult classify(String text) {
        if (text == null || text.isBlank()) {
            return ClassificationResult.of(NoteType.NOTE, 0.3, "пустой текст → NOTE по умолчанию");
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .header("Content-Type", "application/json; charset=utf-8")
                .timeout(config.timeout())
                .POST(HttpRequest.BodyPublishers.ofString(body(text), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (ConnectException e) {
            // Самая частая причина: Ollama не запущена. Говорим об этом прямо,
            // а не общим «сеть недоступна» — иначе непонятно, что чинить.
            throw new ClassificationFailedException(
                    "Ollama не отвечает на " + endpoint + " — запущена ли она?", e);
        } catch (IOException e) {
            throw new ClassificationFailedException("Ollama недоступна: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ClassificationFailedException("запрос к Ollama прерван", e);
        }

        if (response.statusCode() / 100 != 2) {
            String body = response.body();
            // Отдельно разбираем «модель не скачана» — иначе пользователь увидит
            // невнятный HTTP 404 и не догадается выполнить `ollama pull`.
            if (response.statusCode() == 404 && body != null && body.contains("not found")) {
                throw new ClassificationFailedException(
                        "модель «" + config.model() + "» не скачана — выполни: ollama pull "
                                + config.model());
            }
            throw new ClassificationFailedException(
                    "Ollama отвергла запрос (HTTP " + response.statusCode() + "): " + shorten(body));
        }
        return parse(extractContent(response.body()));
    }

    /**
     * Загружает модель в память заранее.
     *
     * <p>Замерено на машине владельца: запрос к выгруженной модели занимает
     * ~11 секунд, к загруженной — ~2,4. Лимит ожидания — 8 секунд, поэтому
     * без прогрева первая мысль после простоя гарантированно достаётся
     * правилам, а не модели.
     *
     * <p>Ollama умеет это без выдумок: запрос с пустым списком сообщений
     * только загружает модель и сразу отвечает. Настоящий текст никуда
     * не отправляется.
     */
    @Override
    public void warmUp() {
        ObjectNode root = JSON.createObjectNode();
        root.put("model", config.model());
        root.putArray("messages");
        root.put("keep_alive", config.keepAlive());

        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .header("Content-Type", "application/json; charset=utf-8")
                // Прогрев не связан лимитом захвата: он никого не задерживает,
                // а загрузка с диска заведомо дольше восьми секунд.
                .timeout(WARMUP_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(root.toString(), StandardCharsets.UTF_8))
                .build();

        try {
            http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ClassificationFailedException("прогрев Ollama не удался: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ClassificationFailedException("прогрев Ollama прерван", e);
        }
    }

    private String body(String text) {
        ObjectNode root = JSON.createObjectNode();
        root.put("model", config.model());
        // Без потоковой выдачи: нам нужен готовый объект, а не текст по кускам.
        root.put("stream", false);

        ArrayNode messages = root.putArray("messages");
        messages.addObject().put("role", "system").put("content", ClassificationPrompt.SYSTEM);
        messages.addObject().put("role", "user").put("content", text);

        // Схема ответа: Ollama ограничивает декодирование ею,
        // поэтому форма гарантируется, а не «ожидается».
        root.set("format", ClassificationPrompt.jsonSchema(JSON));

        // Сколько держать модель в памяти после ответа. Без этого Ollama
        // выгружает её через 5 минут, и следующая мысль снова ждёт загрузки
        // с диска — дольше, чем мы готовы ждать.
        root.put("keep_alive", config.keepAlive());

        ObjectNode options = root.putObject("options");
        // Классификация — не творческая задача: просим наиболее вероятный ответ.
        options.put("temperature", 0);
        // Ответ короткий; ограничение бережёт время на слабом железе.
        options.put("num_predict", 150);

        return root.toString();
    }

    private static String extractContent(String responseBody) {
        try {
            JsonNode root = JSON.readTree(responseBody);
            String content = root.path("message").path("content").asString("");
            if (content.isBlank()) {
                throw new ClassificationFailedException("ответ Ollama пуст");
            }
            return content;
        } catch (JacksonException e) {
            throw new ClassificationFailedException(
                    "ответ Ollama не разбирается: " + e.getMessage(), e);
        }
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
