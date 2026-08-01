package com.secondbrain.notion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Страховка от самой дорогой ошибки в этом проекте: тест, создающий страницы
 * в НАСТОЯЩЕМ Notion пользователя.
 *
 * <p>Пока приложение запускалось только вручную, риска не было — тесты собирали
 * {@link NotionConfig} сами. Но любой тест, поднимающий приложение целиком, вызовет
 * {@link NotionConfig#load()}, и без предохранителя тот прочитал бы боевой
 * {@code notion.properties} из рабочего каталога вместе с рабочим токеном.
 *
 * <p>Предохранитель настроен в surefire (см. pom.xml). Этот тест проверяет,
 * что он на месте и работает.
 */
class NotionConfigSafetyTest {

    @Test
    @DisplayName("В тестах load() не находит боевых настроек и остаётся выключенным")
    void loadIsDisabledUnderTests() {
        NotionConfig config = NotionConfig.load();

        assertFalse(config.isEnabled(),
                "Тесты не должны иметь рабочей интеграции с Notion — иначе они начнут "
                        + "создавать страницы в личном workspace. Проверь systemPropertyVariables "
                        + "и environmentVariables у surefire в pom.xml.");
        assertNull(config.token(), "Токен не должен быть доступен тестам");
    }

    @Test
    @DisplayName("Путь к настройкам перекрывается системным свойством")
    void configPathIsOverridable(@TempDir Path dir) throws IOException {
        Path custom = dir.resolve("custom-notion.properties");
        Files.writeString(custom, """
                notion.token=test-token
                notion.db.task=db-task-id
                """);

        NotionConfig config = NotionConfig.load(custom);

        assertTrue(config.isEnabled());
        assertEquals("test-token", config.token());
        assertEquals("db-task-id", config.databaseId(com.secondbrain.model.NoteType.TASK));
    }

    @Test
    @DisplayName("Отсутствующий файл настроек не роняет приложение")
    void missingFileIsHarmless(@TempDir Path dir) {
        NotionConfig config = NotionConfig.load(dir.resolve("does-not-exist.properties"));

        assertFalse(config.isEnabled());
        assertTrue(config.disabledReason().contains("токен"), config.disabledReason());
    }
}
