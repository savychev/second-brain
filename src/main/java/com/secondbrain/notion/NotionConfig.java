package com.secondbrain.notion;

import com.secondbrain.model.NoteType;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumMap;
import java.util.Map;
import java.util.Properties;

/**
 * Настройки доступа к Notion: секретный токен и id баз под каждый тип заметки.
 *
 * <p>Источники настроек, по возрастанию приоритета:
 * <ol>
 *   <li>файл {@code notion.properties} в корне проекта (не коммитится);</li>
 *   <li>переменные окружения — перекрывают файл (удобно для CI и хостинга).</li>
 * </ol>
 *
 * <p>Если токен не задан, конфигурация считается «выключенной»
 * ({@link #isEnabled()} вернёт {@code false}) — приложение продолжает работать
 * локально, просто не отправляет заметки в Notion. Это осознанное решение:
 * захват мыслей не должен ломаться из-за ненастроенной интеграции.
 */
public final class NotionConfig {

    /** Имя файла с локальными настройками (в .gitignore). */
    public static final String CONFIG_FILE = "notion.properties";

    /**
     * Системное свойство, перекрывающее путь к файлу настроек.
     *
     * <p>Существует ради безопасности тестов: сборка направляет его на заведомо
     * отсутствующий файл, поэтому ни один тест не может случайно взять боевой токен
     * и начать создавать страницы в настоящем Notion. См. surefire в pom.xml.
     */
    public static final String CONFIG_PATH_PROPERTY = "secondbrain.notion.config";

    private static final String ENV_TOKEN = "NOTION_TOKEN";
    private static final String PROP_TOKEN = "notion.token";

    private final String token;
    private final Map<NoteType, String> databaseIds;

    NotionConfig(String token, Map<NoteType, String> databaseIds) {
        this.token = token;
        this.databaseIds = new EnumMap<>(databaseIds);
    }

    /**
     * Загружает настройки из файла и переменных окружения.
     *
     * <p>Путь к файлу можно перекрыть системным свойством {@link #CONFIG_PATH_PROPERTY}
     * или переменной окружения {@code NOTION_CONFIG_FILE}.
     */
    public static NotionConfig load() {
        String override = System.getProperty(CONFIG_PATH_PROPERTY);
        if (override == null || override.isBlank()) {
            override = System.getenv("NOTION_CONFIG_FILE");
        }
        Path file = (override != null && !override.isBlank())
                ? Paths.get(override.trim())
                : Paths.get(CONFIG_FILE);
        return load(file);
    }

    /** Загружает настройки из указанного файла (переменные окружения имеют приоритет). */
    public static NotionConfig load(Path configFile) {
        Properties props = readProperties(configFile);

        String token = firstNonBlank(System.getenv(ENV_TOKEN), props.getProperty(PROP_TOKEN));

        Map<NoteType, String> ids = new EnumMap<>(NoteType.class);
        for (NoteType type : NoteType.values()) {
            String fromEnv = System.getenv("NOTION_DB_" + type.name());
            String fromFile = props.getProperty("notion.db." + type.name().toLowerCase());
            String id = firstNonBlank(fromEnv, fromFile);
            if (id != null) {
                ids.put(type, id);
            }
        }
        return new NotionConfig(token, ids);
    }

    private static Properties readProperties(Path file) {
        Properties props = new Properties();
        if (file != null && Files.isReadable(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
            } catch (IOException e) {
                // Настройки — не критичный ресурс: без них просто работаем локально.
                System.err.println("Не удалось прочитать " + file + ": " + e.getMessage());
            }
        }
        return props;
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

    /** Настроена ли интеграция: есть токен и хотя бы одна база. */
    public boolean isEnabled() {
        return token != null && !databaseIds.isEmpty();
    }

    public String token() {
        return token;
    }

    /** id базы Notion для типа заметки; {@code null}, если база не настроена. */
    public String databaseId(NoteType type) {
        return databaseIds.get(type);
    }

    /** Человекочитаемое объяснение, почему интеграция выключена (для подсказки пользователю). */
    public String disabledReason() {
        if (token == null) {
            return "не задан токен (" + ENV_TOKEN + " или " + PROP_TOKEN + " в " + CONFIG_FILE + ")";
        }
        if (databaseIds.isEmpty()) {
            return "не заданы id баз Notion в " + CONFIG_FILE;
        }
        return "";
    }
}
