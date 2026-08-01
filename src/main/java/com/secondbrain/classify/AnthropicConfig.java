package com.secondbrain.classify;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Properties;

/**
 * Настройки умной классификации через Anthropic API.
 *
 * <p>Источники, по возрастанию приоритета: файл {@code anthropic.properties}
 * в корне проекта (не коммитится), затем переменные окружения.
 *
 * <p>Без ключа классификация остаётся на правилах — приложение работает как прежде.
 * Это осознанное решение: захват мысли не должен зависеть от внешнего сервиса
 * и от наличия оплаченного доступа.
 */
public final class AnthropicConfig {

    public static final String CONFIG_FILE = "anthropic.properties";
    public static final String CONFIG_PATH_PROPERTY = "secondbrain.anthropic.config";

    private static final String ENV_KEY = "ANTHROPIC_API_KEY";

    /**
     * Модель по умолчанию. Claude Opus 5 — текущая рекомендованная;
     * на уровне усилий «low» она для такой задачи быстрая и недорогая.
     */
    private static final String DEFAULT_MODEL = "claude-opus-5";

    /**
     * Предел ожидания ответа. Выбран из цели PRD «захват за ≤10 секунд»:
     * если модель не ответила за это время, классифицируем правилами и не задерживаем
     * пользователя. Лучше быстрый и слегка неточный ответ, чем точный и поздний.
     */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(8);

    private final String apiKey;
    private final String model;
    private final Duration timeout;

    AnthropicConfig(String apiKey, String model, Duration timeout) {
        this.apiKey = apiKey;
        this.model = (model == null || model.isBlank()) ? DEFAULT_MODEL : model.trim();
        this.timeout = (timeout == null) ? DEFAULT_TIMEOUT : timeout;
    }

    /** Загружает настройки из файла и переменных окружения. */
    public static AnthropicConfig load() {
        String override = System.getProperty(CONFIG_PATH_PROPERTY);
        if (override == null || override.isBlank()) {
            override = System.getenv("ANTHROPIC_CONFIG_FILE");
        }
        Path file = (override != null && !override.isBlank())
                ? Paths.get(override.trim())
                : Paths.get(CONFIG_FILE);
        return load(file);
    }

    /** @see #load() */
    public static AnthropicConfig load(Path configFile) {
        Properties props = readProperties(configFile);

        String key = firstNonBlank(System.getenv(ENV_KEY), props.getProperty("anthropic.api.key"));
        String model = firstNonBlank(System.getenv("ANTHROPIC_MODEL"),
                props.getProperty("anthropic.model"));
        Duration timeout = parseSeconds(firstNonBlank(System.getenv("ANTHROPIC_TIMEOUT_SECONDS"),
                props.getProperty("anthropic.timeout.seconds")));

        return new AnthropicConfig(key, model, timeout);
    }

    private static Properties readProperties(Path file) {
        Properties props = new Properties();
        if (file != null && Files.isReadable(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
            } catch (IOException e) {
                System.err.println("Не удалось прочитать " + file + ": " + e.getMessage());
            }
        }
        return props;
    }

    private static Duration parseSeconds(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Duration.ofSeconds(Long.parseLong(value.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.trim();
        }
        if (b != null && !b.isBlank()) {
            return b.trim();
        }
        return null;
    }

    /** Настроена ли умная классификация. */
    public boolean isEnabled() {
        return apiKey != null;
    }

    public String apiKey() {
        return apiKey;
    }

    public String model() {
        return model;
    }

    public Duration timeout() {
        return timeout;
    }

    /** Почему умная классификация выключена — для подсказки пользователю. */
    public String disabledReason() {
        return "не задан ключ (" + ENV_KEY + " или anthropic.api.key в " + CONFIG_FILE + ")";
    }
}
