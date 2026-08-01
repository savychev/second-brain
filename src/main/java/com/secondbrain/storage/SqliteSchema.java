package com.secondbrain.storage;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Создание таблиц при старте.
 *
 * <p>Скрипт {@code schema.sql} идемпотентен, поэтому выполняется при каждом запуске:
 * на пустом месте база создаётся сама, на существующей ничего не меняется.
 * Отдельный инструмент версионных миграций (Flyway, Liquibase) для одной таблицы
 * избыточен — он появится вместе с PostgreSQL.
 */
public final class SqliteSchema {

    private static final String SCRIPT = "schema.sql";

    private SqliteSchema() {
    }

    /** Выполняет {@code schema.sql}. Безопасно вызывать многократно. */
    public static void apply(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            // Кодировку указываем явно: в скрипте есть комментарии на кириллице,
            // а кодировка по умолчанию на Windows — не UTF-8.
            ScriptUtils.executeSqlScript(connection,
                    new EncodedResource(new ClassPathResource(SCRIPT), StandardCharsets.UTF_8));
        } catch (SQLException e) {
            throw new IllegalStateException("Не удалось создать схему базы из " + SCRIPT, e);
        }
    }
}
