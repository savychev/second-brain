package com.secondbrain.storage;

import org.sqlite.SQLiteConfig;
import org.sqlite.javax.SQLiteConnectionPoolDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Создание подключения к файлу базы SQLite.
 *
 * <p>Настройки заданы здесь, а не автоматикой Spring, потому что то же самое
 * подключение нужно консольному режиму, где Spring-контекста нет вовсе.
 */
public final class SqliteFiles {

    private SqliteFiles() {
    }

    /**
     * Готовит источник подключений к указанному файлу базы.
     *
     * <p>Что настраивается и зачем:
     * <ul>
     *   <li><b>абсолютный путь</b> — SQLite при относительном пути создаёт базу
     *       относительно текущего каталога. Запустив программу из другого места,
     *       пользователь молча получил бы новую пустую базу и решил, что заметки пропали;</li>
     *   <li><b>журнал WAL</b> — читатели не блокируют писателя; нужно, когда
     *       сервер и консоль работают с одним файлом;</li>
     *   <li><b>busy_timeout</b> — второй писатель ждёт освобождения до 5 секунд,
     *       а не падает сразу с «database is locked».</li>
     * </ul>
     *
     * @param dbFile путь к файлу базы; недостающие каталоги создаются
     */
    public static DataSource dataSource(Path dbFile) {
        Path absolute = dbFile.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                throw new UncheckedIOException("Не удалось создать каталог для базы: " + parent, e);
            }
        }

        SQLiteConfig config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setBusyTimeout(5000);
        config.enforceForeignKeys(true);

        SQLiteConnectionPoolDataSource dataSource = new SQLiteConnectionPoolDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + absolute);
        return dataSource;
    }
}
