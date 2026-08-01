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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Выбор провайдера классификации и предохранитель, не дающий тестам
 * обращаться к внешним сервисам.
 */
class ClassifiersTest {

    private Path config(Path dir, String content) throws IOException {
        Path file = dir.resolve("classifier.properties");
        Files.writeString(file, content);
        return file;
    }

    @Test
    @DisplayName("В тестах классификация моделью выключена — сеть не трогаем")
    void modelClassificationIsDisabledUnderTests() {
        ProviderConfig config = ProviderConfig.load();

        assertFalse(config.isEnabled(),
                "Тесты не должны обращаться к внешним сервисам. Проверь "
                        + "systemPropertyVariables и environmentVariables у surefire в pom.xml.");
        assertInstanceOf(RuleBasedClassifier.class, Classifiers.create(config));
    }

    @Test
    @DisplayName("Ключ Groq → бесплатный провайдер с подстраховкой правилами")
    void groqKeyEnablesGroq(@TempDir Path dir) throws IOException {
        ProviderConfig config = ProviderConfig.load(config(dir, "groq.api.key=test-not-real\n"));

        assertTrue(config.isEnabled());
        assertEquals(ProviderConfig.GROQ, config.provider());
        assertEquals("openai/gpt-oss-20b", config.model(), "модель по умолчанию для Groq");
        assertInstanceOf(FallbackClassifier.class, Classifiers.create(config),
                "модель обязана идти в паре с правилами");
    }

    @Test
    @DisplayName("Только ключ Anthropic → выбирается он")
    void anthropicKeyEnablesAnthropic(@TempDir Path dir) throws IOException {
        ProviderConfig config = ProviderConfig.load(
                config(dir, "anthropic.api.key=test-not-real\n"));

        assertEquals(ProviderConfig.ANTHROPIC, config.provider());
        assertEquals("claude-opus-5", config.model());
    }

    @Test
    @DisplayName("Есть оба ключа → выбирается бесплатный")
    void prefersFreeProviderWhenBothAvailable(@TempDir Path dir) throws IOException {
        ProviderConfig config = ProviderConfig.load(config(dir, """
                groq.api.key=test-not-real
                anthropic.api.key=test-not-real
                """));

        assertEquals(ProviderConfig.GROQ, config.provider(),
                "по умолчанию не тратим деньги без явной просьбы");
    }

    @Test
    @DisplayName("Провайдер можно выбрать явно, вопреки порядку по умолчанию")
    void explicitProviderWins(@TempDir Path dir) throws IOException {
        ProviderConfig config = ProviderConfig.load(config(dir, """
                classifier.provider=anthropic
                groq.api.key=test-not-real
                anthropic.api.key=test-not-real
                """));

        assertEquals(ProviderConfig.ANTHROPIC, config.provider());
    }

    @Test
    @DisplayName("Правила можно выбрать явно, даже при наличии ключей")
    void rulesCanBeForced(@TempDir Path dir) throws IOException {
        ProviderConfig config = ProviderConfig.load(config(dir, """
                classifier.provider=rules
                groq.api.key=test-not-real
                """));

        assertFalse(config.isEnabled());
        assertInstanceOf(RuleBasedClassifier.class, Classifiers.create(config));
    }

    @Test
    @DisplayName("Опечатка в провайдере — громкая ошибка, а не тихий уход на другой")
    void unknownProviderFailsLoudly(@TempDir Path dir) throws IOException {
        Path file = config(dir, "classifier.provider=grok\ngroq.api.key=test-not-real\n");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ProviderConfig.load(file));

        assertTrue(e.getMessage().contains("grok"), e.getMessage());
    }

    @Test
    @DisplayName("Выбран провайдер без ключа — тоже громкая ошибка")
    void missingKeyForRequestedProviderFailsLoudly(@TempDir Path dir) throws IOException {
        Path file = config(dir, "classifier.provider=groq\n");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ProviderConfig.load(file));

        assertTrue(e.getMessage().contains("GROQ_API_KEY"),
                "в сообщении должно быть, какой ключ задать: " + e.getMessage());
    }

    @Test
    @DisplayName("Таймаут укладывается в цель «захват за 10 секунд»")
    void timeoutFitsCaptureGoal(@TempDir Path dir) throws IOException {
        ProviderConfig config = ProviderConfig.load(config(dir, "groq.api.key=test-not-real\n"));

        assertTrue(config.timeout().toSeconds() <= 10,
                "иначе медленный ответ модели сорвёт цель PRD");
    }

    @Test
    @DisplayName("Описание честно говорит, как классифицируются мысли")
    void describeIsHonest(@TempDir Path dir) throws IOException {
        String rules = ProviderConfig.load(dir.resolve("нет-такого.properties")).describe();
        assertTrue(rules.contains("правила"), rules);

        String groq = ProviderConfig.load(config(dir, "groq.api.key=test-not-real\n")).describe();
        assertTrue(groq.contains("groq"), groq);
        assertTrue(groq.contains("правила"), "подстраховка должна быть упомянута: " + groq);
    }
}
