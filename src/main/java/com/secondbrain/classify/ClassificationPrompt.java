package com.secondbrain.classify;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Правила классификации и форма ответа — общие для всех моделей.
 *
 * <p>Живут отдельно, потому что от провайдера не зависят: и Anthropic, и Groq
 * получают тот же текст и ту же схему. Если бы промпт лежал внутри реализации,
 * два провайдера со временем начали бы классифицировать по-разному, и сравнить
 * их качество стало бы невозможно.
 */
public final class ClassificationPrompt {

    private ClassificationPrompt() {
    }

    /**
     * Системный промпт: правила классификации.
     *
     * <p>Намеренно объясняет <i>принцип</i>, а не перечисляет слова-триггеры —
     * именно этим модель и отличается от классификатора по правилам. Разбор
     * пограничных случаев описан теми же словами, что и в правилах, чтобы
     * поведение двух классификаторов не расходилось на очевидных примерах.
     */
    public static final String SYSTEM = """
            Классифицируй мысль. Верни ТОЛЬКО объект.

            type — одна категория:
            TASK — нужно сделать: дело, покупка, звонок, напоминание,
                   бытовое дело («заменить лампочку», «полить цветы»)
            IDEA — замысел: «а что если», «было бы круто», «можно добавить»
            LINK — ссылка, сохраняемая на потом
            NOTE — наблюдение, впечатление, факт, размышление

            Спорные случаи:
            «отправить ссылку коллеге» → TASK (действие важнее ссылки)
            «надо сделать тёмную тему» → TASK, «было бы круто тёмную тему» → IDEA

            confidence — от 0.0 до 1.0, честно.

            reason — почему выбрана категория. НЕ БОЛЕЕ 6 СЛОВ,
                     НА ЯЗЫКЕ МЫСЛИ.

            tags — до 2 тем, одним словом, НА ЯЗЫКЕ МЫСЛИ
                   (дом, работа, здоровье, финансы, покупки, учёба, быт).
                   Тема — это НЕ категория: НЕ пиши TASK, IDEA, LINK, NOTE.
                   Тема — это НЕ пересказ мысли.
                   Подходящей темы нет — верни пустой список.

            Опечатки и разговорная форма — норма, понимай смысл.
            """;

    /**
     * Форма ответа в виде JSON Schema.
     *
     * <p>Строится заново для переданного {@code mapper}, чтобы узлы принадлежали
     * тому же экземпляру, который будет их сериализовать.
     */
    public static ObjectNode jsonSchema(ObjectMapper mapper) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);

        ObjectNode properties = schema.putObject("properties");

        ObjectNode type = properties.putObject("type");
        type.put("type", "string");
        type.put("description", "Категория мысли");
        ArrayNode allowed = type.putArray("enum");
        allowed.add("IDEA").add("TASK").add("LINK").add("NOTE");

        ObjectNode confidence = properties.putObject("confidence");
        confidence.put("type", "number");
        confidence.put("description", "Уверенность от 0.0 до 1.0");

        ObjectNode reason = properties.putObject("reason");
        reason.put("type", "string");
        reason.put("description", "Одна короткая фраза о причине выбора");

        ObjectNode tags = properties.putObject("tags");
        tags.put("type", "array");
        tags.put("description", "До трёх тем одним словом в нижнем регистре");
        tags.putObject("items").put("type", "string");

        ArrayNode required = schema.putArray("required");
        required.add("type").add("confidence").add("reason").add("tags");

        return schema;
    }
}
