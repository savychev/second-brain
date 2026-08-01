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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Классификация мысли моделью Claude.
 *
 * <p>Заменяет {@link RuleBasedClassifier} на этапе 4. Правила остаются в проекте
 * и служат запасным вариантом: см. {@link FallbackClassifier}. Это не перестраховка,
 * а требование «ноль потерь» — захват мысли не должен зависеть от доступности
 * внешнего сервиса.
 *
 * <p>Две вещи, ради которых стоит смотреть в код:
 * <ul>
 *   <li><b>Схема ответа задана явно.</b> Модель обязана вернуть объект ровно
 *       описанной формы — это называется «структурированный вывод». Разбирать
 *       свободный текст и гадать, что имелось в виду, не нужно.</li>
 *   <li><b>Системный промпт кэшируется.</b> Он одинаков для каждой мысли, поэтому
 *       после первого запроса оплачивается по льготной ставке.</li>
 * </ul>
 */
public class AnthropicClassifier implements Classifier {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    /**
     * Системный промпт: правила классификации.
     *
     * <p>Намеренно объясняет <i>принцип</i>, а не перечисляет слова-триггеры —
     * именно этим модель и отличается от классификатора по правилам. Разбор
     * пограничных случаев здесь описан теми же словами, что и в правилах,
     * чтобы поведение двух классификаторов не расходилось.
     */
    private static final String SYSTEM_PROMPT = """
            Ты — классификатор мыслей в личной системе захвата заметок.
            Пользователь сбрасывает мысли одной строкой; твоя задача — определить,
            что это, и предложить теги.

            КАТЕГОРИИ:
            - TASK — то, что нужно сделать. Дело, поручение, покупка, звонок,
              напоминание. Признак: есть действие, которое предстоит выполнить.
            - IDEA — замысел, предложение, «а что если». Признак: описывает то,
              чего пока нет, и не является прямым поручением себе.
            - LINK — ссылка или ресурс, сохраняемый, чтобы вернуться позже.
              Признак: суть мысли в самом адресе, а не в действии с ним.
            - NOTE — всё остальное: наблюдение, впечатление, факт, размышление.

            РАЗБОР ПОГРАНИЧНЫХ СЛУЧАЕВ:
            - Действие важнее ссылки: «отправить ссылку коллеге» — это TASK,
              а не LINK. Голая ссылка без действия — LINK.
            - Действие важнее идеи: «надо сделать тёмную тему» — это TASK,
              «было бы круто иметь тёмную тему» — IDEA.
            - Опечатки и разговорная форма — норма, понимай смысл, а не буквы.
            - Мысль может быть на любом языке; отвечай на языке мысли.

            ТЕГИ:
            Предложи до трёх тегов одним словом в нижнем регистре — тему, к которой
            относится мысль (например: дом, работа, здоровье, финансы, покупки,
            учёба). Теги должны помогать искать заметку позже, а не пересказывать её.
            Если подходящих тем нет — верни пустой список; выдумывать не нужно.

            УВЕРЕННОСТЬ:
            Честно оценивай от 0.0 до 1.0. Низкая уверенность — нормальный ответ
            для действительно неоднозначной мысли.

            ПРИЧИНА:
            Одна короткая фраза о том, почему выбрана категория. Пиши для человека,
            который потом будет разбираться, почему заметка попала не туда.
            """;

    private final AnthropicClient client;
    private final AnthropicConfig config;

    public AnthropicClassifier(AnthropicConfig config) {
        this(config, defaultClient(config));
    }

    /** Конструктор для тестов: позволяет подставить свой клиент. */
    public AnthropicClassifier(AnthropicConfig config, AnthropicClient client) {
        this.config = config;
        this.client = client;
    }

    private static AnthropicClient defaultClient(AnthropicConfig config) {
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

        Message response = client.messages().create(request(text));

        // Модель может отказаться отвечать — это штатный исход, а не ошибка.
        // Проверяем ДО чтения содержимого: при отказе оно пустое.
        if (response.stopReason().filter(r -> r.equals(StopReason.REFUSAL)).isPresent()) {
            throw new ClassificationFailedException("модель отклонила запрос");
        }

        String json = firstText(response);
        return parse(json);
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
                        .format(responseSchema())
                        .build())
                // Системный промпт одинаков для каждой мысли, поэтому помечаем его
                // для кэширования: со второго запроса он оплачивается по льготной ставке.
                .systemOfTextBlockParams(List.of(
                        TextBlockParam.builder()
                                .text(SYSTEM_PROMPT)
                                .cacheControl(CacheControlEphemeral.builder().build())
                                .build()))
                .addUserMessage(text)
                .build();
    }

    /** Форма ответа, которую модель обязана соблюсти. */
    private static JsonOutputFormat responseSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("type", Map.of(
                "type", "string",
                "enum", List.of("IDEA", "TASK", "LINK", "NOTE"),
                "description", "Категория мысли"));
        properties.put("confidence", Map.of(
                "type", "number",
                "description", "Уверенность от 0.0 до 1.0"));
        properties.put("reason", Map.of(
                "type", "string",
                "description", "Одна короткая фраза о причине выбора"));
        properties.put("tags", Map.of(
                "type", "array",
                "items", Map.of("type", "string"),
                "description", "До трёх тем одним словом в нижнем регистре"));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("type", "confidence", "reason", "tags"));
        schema.put("additionalProperties", false);

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
        } catch (RuntimeException e) {
            throw new ClassificationFailedException("ответ модели не разбирается: " + e.getMessage(), e);
        }

        NoteType type = parseType(node.path("type").asString(""));
        double confidence = clamp(node.path("confidence").asDouble(0.5));
        String reason = node.path("reason").asString("определено моделью");

        List<String> tags = new ArrayList<>();
        JsonNode tagsNode = node.path("tags");
        if (tagsNode.isArray()) {
            for (JsonNode tag : tagsNode) {
                String value = tag.asString("").trim().toLowerCase(Locale.ROOT);
                if (!value.isEmpty() && !tags.contains(value)) {
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
            // Схема запрещает такое, но полагаться на это без проверки нельзя.
            throw new ClassificationFailedException("модель вернула неизвестный тип: «" + value + "»");
        }
    }

    private static double clamp(double value) {
        return Math.clamp(value, 0.0, 1.0);
    }
}
