package com.secondbrain.telegram;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;

/**
 * Настройки Telegram-бота: токен и список разрешённых чатов.
 *
 * <p>Источники, по возрастанию приоритета: файл {@code telegram.properties}
 * в корне проекта (не коммитится), затем переменные окружения.
 *
 * <p>Без токена бот просто не запускается — всё остальное работает как прежде.
 */
public final class TelegramConfig {

    public static final String CONFIG_FILE = "telegram.properties";
    public static final String CONFIG_PATH_PROPERTY = "secondbrain.telegram.config";

    private static final String ENV_TOKEN = "TELEGRAM_BOT_TOKEN";

    /**
     * Сколько Telegram держит запрос открытым, ожидая новых сообщений.
     * Тридцать секунд — рекомендованное значение: сообщения приходят мгновенно,
     * а запросов уходит два в минуту вместо непрерывного опроса.
     */
    private static final Duration DEFAULT_POLL = Duration.ofSeconds(30);

    private final String token;
    private final Set<Long> allowedChats;
    private final Duration pollTimeout;

    TelegramConfig(String token, Set<Long> allowedChats, Duration pollTimeout) {
        this.token = token;
        this.allowedChats = Set.copyOf(allowedChats);
        this.pollTimeout = (pollTimeout == null) ? DEFAULT_POLL : pollTimeout;
    }

    /** Загружает настройки из файла и переменных окружения. */
    public static TelegramConfig load() {
        String override = System.getProperty(CONFIG_PATH_PROPERTY);
        if (override == null || override.isBlank()) {
            override = System.getenv("TELEGRAM_CONFIG_FILE");
        }
        Path file = (override != null && !override.isBlank())
                ? Paths.get(override.trim())
                : Paths.get(CONFIG_FILE);
        return load(file);
    }

    /** @see #load() */
    public static TelegramConfig load(Path configFile) {
        Properties props = readProperties(configFile);

        String token = value(ENV_TOKEN, props, "telegram.bot.token");
        Set<Long> allowed = parseChats(value("TELEGRAM_ALLOWED_CHATS", props,
                "telegram.allowed.chats"));
        Duration poll = parseSeconds(value("TELEGRAM_POLL_SECONDS", props,
                "telegram.poll.seconds"));

        return new TelegramConfig(token, allowed, poll);
    }

    /** Разбирает список идентификаторов чатов через запятую. */
    private static Set<Long> parseChats(String value) {
        Set<Long> chats = new LinkedHashSet<>();
        if (value == null) {
            return chats;
        }
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                chats.add(Long.parseLong(trimmed));
            } catch (NumberFormatException e) {
                // Молча пропустить — значит тихо потерять разрешение и потом
                // недоумевать, почему бот не отвечает.
                throw new IllegalArgumentException(
                        "Некорректный идентификатор чата: «" + trimmed + "» в "
                                + CONFIG_FILE + ". Ожидается число.");
            }
        }
        return chats;
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

    private static String value(String envName, Properties props, String propertyName) {
        String fromEnv = System.getenv(envName);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.trim();
        }
        String fromFile = props.getProperty(propertyName);
        return (fromFile != null && !fromFile.isBlank()) ? fromFile.trim() : null;
    }

    private static Duration parseSeconds(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Duration.ofSeconds(Long.parseLong(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Настроен ли бот. */
    public boolean isEnabled() {
        return token != null;
    }

    public String token() {
        return token;
    }

    public Duration pollTimeout() {
        return pollTimeout;
    }

    /**
     * Разрешено ли чату писать в систему.
     *
     * <p>Пустой список означает, что не разрешено <b>никому</b>. Это осознанно:
     * бота можно найти по имени, и «пусто = всем можно» превратило бы личную
     * систему заметок в открытую форму для кого угодно. Свой идентификатор
     * пользователь узнаёт из ответа бота на первое сообщение.
     */
    public boolean isAllowed(long chatId) {
        return allowedChats.contains(chatId);
    }

    /** Настроен ли хоть один разрешённый чат. */
    public boolean hasAllowedChats() {
        return !allowedChats.isEmpty();
    }

    /** Как настроен бот — для показа пользователю. */
    public String describe() {
        if (!isEnabled()) {
            return "выключен (нет токена в " + CONFIG_FILE + ")";
        }
        if (!hasAllowedChats()) {
            return "включён, но НИ ОДИН чат не разрешён — напиши боту, он подскажет твой id";
        }
        return "включён, разрешённых чатов: " + allowedChats.size();
    }
}
