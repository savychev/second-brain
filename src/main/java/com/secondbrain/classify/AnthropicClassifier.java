package com.secondbrain.classify;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import com.secondbrain.model.NoteType;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Классификация мысли моделью Claude.
 *
 * <p>Платный вариант: даёт лучшее качество, но требует пополненного счёта
 * в Anthropic Console (подписка Claude Pro/Max доступа к API <b>не</b> включает).
 * Бесплатная альтернатива — {@link GroqClassifier}; выбор между ними
 * делается настройкой, см. {@link ProviderConfig}.
 *
 * <p>Правила классификации и форма ответа общие для всех провайдеров и живут
 * в {@link ClassificationPrompt} — иначе два классификатора со временем начали бы
 * вести себя по-разному, и сравнить их стало бы невозможно.
 */
public class AnthropicClassifier implements Classifier {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final AnthropicClient client;
    private final ProviderConfig config;

    public AnthropicClassifier(ProviderConfig config) {
        this(config, defaultClient(config));
    }

    /** Конструктор для тестов: позволяет подставить свой клиент. */
    public AnthropicClassifier(ProviderConfig config, AnthropicClient client) {
        this.config = config;
        this.client = client;
    }

    private static AnthropicClient defaultClient(ProviderConfig config) {
        return AnthropicOkHttpClient.builder()
                .apiKey(config.apiKey())
                .timeout(config.timeout())
                .build();
    }

    @Override
    public ClassificationResult classify(String text) {
        if (text == null || text.isBlank()) {
            return ClassificationResult.of(NoteType.NOTE, 0.3, "пустой текст → NOTE по умолчанию");
        }

        Message response;
        try {
            response = client.messages().create(request(text));
        } catch (RuntimeException e) {
            throw new ClassificationFailedException("Anthropic недоступен: " + e.getMessage(), e);
        }

        // Модель может отклонить запрос — это штатный исход, а не ошибка.
        // Проверяем ДО чтения содержимого: при отказе оно пустое.
        if (response.stopReason().filter(StopReason.REFUSAL::equals).isPresent()) {
            throw new ClassificationFailedException("модель отклонила запрос");
        }
        return parse(firstText(response));
    }

    private MessageCreateParams request(String text) {
        return MessageCreateParams.builder()
                .model(config.model())
                // Запас на размышление: на Opus 5 оно включено по умолчанию,
                // а max_tokens ограничивает размышление и ответ вместе.
                .maxTokens(2000L)
                // ВАЖНО: effort и схему задаём ОДНИМ вызовом. Два отдельных
                // вызова outputConfig затирают друг друга молча — проверено.
                .outputConfig(OutputConfig.builder()
                        .effort(OutputConfig.Effort.LOW)
                        .format(responseFormat())
                        .build())
                // Системный промпт одинаков для каждой мысли, поэтому помечаем его
                // для кэширования: при захвате нескольких мыслей подряд повторные
                // запросы оплачиваются по льготной ставке.
                .systemOfTextBlockParams(List.of(
                        TextBlockParam.builder()
                                .text(ClassificationPrompt.SYSTEM)
                                .cacheControl(CacheControlEphemeral.builder().build())
                                .build()))
                .addUserMessage(text)
                .build();
    }

    /** Общая схема, переведённая в форму, которую понимает SDK. */
    private static JsonOutputFormat responseFormat() {
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = JSON.convertValue(
                ClassificationPrompt.jsonSchema(JSON), Map.class);

        return JsonOutputFormat.builder()
                .schema(JsonOutputFormat.Schema.builder()
                        .additionalProperties(schema.entrySet().stream().collect(
                                Collectors.toMap(Map.Entry::getKey,
                                        e -> JsonValue.from(e.getValue()))))
                        .build())
                .build();
    }

    private static String firstText(Message response) {
        return response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(block -> block.text())
                .findFirst()
                .orElseThrow(() -> new ClassificationFailedException("ответ модели пуст"));
    }

    private static ClassificationResult parse(String json) {
        JsonNode node;
        try {
            node = JSON.readTree(json);
        } catch (JacksonException e) {
            throw new ClassificationFailedException(
                    "ответ модели не разбирается: " + e.getMessage(), e);
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
}
