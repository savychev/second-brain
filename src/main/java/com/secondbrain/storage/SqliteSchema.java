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

    /** Сколько раз повторить попытку, если база занята другим процессом. */
    private static final int ATTEMPTS = 5;
    private static final long RETRY_PAUSE_MS = 200;

    /**
     * Выполняет {@code schema.sql}. Безопасно вызывать многократно.
     *
     * <p>С повторными попытками при занятой базе. Это не перестраховка: перевод
     * новой базы в режим WAL требует эксклюзивной блокировки, а на неё
     * {@code busy_timeout} не распространяется — SQLite не вызывает обработчик
     * ожидания для этого случая. Два процесса, одновременно создающие базу с нуля
     * (консоль и сервер после шага 6), иначе роняли бы друг друга.
     */
    public static void apply(DataSource dataSource) {
        SQLException last = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try (Connection connection = dataSource.getConnection()) {
                // Кодировку указываем явно: в скрипте есть комментарии на кириллице,
                // а кодировка по умолчанию на Windows — не UTF-8.
                ScriptUtils.executeSqlScript(connection,
                        new EncodedResource(new ClassPathResource(SCRIPT), StandardCharsets.UTF_8));
                return;
            } catch (SQLException e) {
                last = e;
                if (!isBusy(e) || attempt == ATTEMPTS) {
                    break;
                }
                pause();
            }
        }
        throw new IllegalStateException("Не удалось создать схему базы из " + SCRIPT, last);
    }

    /** Занята ли база другим процессом. */
    private static boolean isBusy(SQLException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String message = t.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("database is locked") || lower.contains("sqlite_busy")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void pause() {
        try {
            Thread.sleep(RETRY_PAUSE_MS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Создание схемы прервано", ie);
        }
    }
}
