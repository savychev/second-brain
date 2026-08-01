package com.secondbrain.classify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Выбор классификатора и предохранитель, не дающий тестам тратить деньги на API.
 */
class ClassifiersTest {

    @Test
    @DisplayName("В тестах умная классификация выключена — API не вызывается")
    void smartClassificationIsDisabledUnderTests() {
        AnthropicConfig config = AnthropicConfig.load();

        assertFalse(config.isEnabled(),
                "Тесты не должны обращаться к платному API. Проверь systemPropertyVariables "
                        + "и environmentVariables у surefire в pom.xml.");
        assertInstanceOf(RuleBasedClassifier.class, Classifiers.create(config),
                "без ключа должны работать правила");
    }

    @Test
    @DisplayName("С ключом собирается умный классификатор с подстраховкой")
    void buildsSmartClassifierWithFallback(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("anthropic.properties");
        Files.writeString(file, """
                anthropic.api.key=test-key-not-real
                anthropic.model=claude-opus-5
                """);

        AnthropicConfig config = AnthropicConfig.load(file);

        assertTrue(config.isEnabled());
        assertEquals("claude-opus-5", config.model());
        assertInstanceOf(FallbackClassifier.class, Classifiers.create(config),
                "умный классификатор обязан идти в паре с правилами");
    }

    @Test
    @DisplayName("Модель по умолчанию задана, даже если в настройках её нет")
    void hasDefaultModel(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("anthropic.properties");
        Files.writeString(file, "anthropic.api.key=test-key-not-real\n");

        assertEquals("claude-opus-5", AnthropicConfig.load(file).model());
    }

    @Test
    @DisplayName("Таймаут ограничен: захват не должен ждать дольше цели «10 секунд»")
    void timeoutFitsCaptureGoal(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("anthropic.properties");
        Files.writeString(file, "anthropic.api.key=test-key-not-real\n");

        AnthropicConfig config = AnthropicConfig.load(file);

        assertTrue(config.timeout().toSeconds() <= 10,
                "иначе медленный ответ модели сорвёт цель «захват за 10 секунд»");
    }

    @Test
    @DisplayName("Описание честно говорит, как классифицируются мысли")
    void describeIsHonest(@TempDir Path dir) throws IOException {
        AnthropicConfig noKey = AnthropicConfig.load(dir.resolve("нет-такого.properties"));
        assertTrue(Classifiers.describe(noKey).contains("правила"), Classifiers.describe(noKey));

        Path file = dir.resolve("anthropic.properties");
        Files.writeString(file, "anthropic.api.key=test-key-not-real\n");
        String withKey = Classifiers.describe(AnthropicConfig.load(file));
        assertTrue(withKey.contains("claude-opus-5"), withKey);
        assertTrue(withKey.contains("правила"), "подстраховка должна быть упомянута: " + withKey);
    }
}
