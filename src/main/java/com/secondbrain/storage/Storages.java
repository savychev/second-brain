package com.secondbrain.storage;

import com.secondbrain.notion.JvmSyncLock;
import com.secondbrain.notion.SqliteSyncLock;
import com.secondbrain.notion.SyncLock;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * Выбор хранилища заметок.
 *
 * <p>Пока идёт переезд с JSON на SQLite, обе реализации живут рядом, а выбор
 * делается одной переменной окружения {@code SECOND_BRAIN_STORAGE}:
 *
 * <pre>
 *   SECOND_BRAIN_STORAGE=json     файл JSON (по умолчанию)
 *   SECOND_BRAIN_STORAGE=sqlite   база SQLite
 * </pre>
 *
 * <p>Смысл переключателя — в возможности отката. Если на SQLite обнаружится
 * проблема, возврат к рабочему состоянию стоит одной переменной, а не
 * восстановления из резервной копии. JSON-реализация остаётся в проекте
 * и после переезда: она же служит источником для импорта.
 *
 * <p>Пути к данным тоже настраиваются:
 * {@code SECOND_BRAIN_DATA} — файл JSON, {@code SECOND_BRAIN_DB} — файл базы.
 */
public final class Storages {

    public static final String ENV_KIND = "SECOND_BRAIN_STORAGE";
    public static final String ENV_JSON_FILE = "SECOND_BRAIN_DATA";
    public static final String ENV_DB_FILE = "SECOND_BRAIN_DB";

    public static final String JSON = "json";
    public static final String SQLITE = "sqlite";

    /**
     * Хранилище по умолчанию — SQLite (с 31.07.2026, после проверенного импорта).
     *
     * <p>Откат на прежнее хранилище: {@code SECOND_BRAIN_STORAGE=json}. Обрати внимание,
     * что откат возвращает файл JSON в том состоянии, в каком он был на момент
     * переключения: заметки, захваченные уже в базу, в нём не появятся.
     * Обратного переноса нет — он не нужен, пока откат делается сразу.
     */
    public static final String DEFAULT_KIND = SQLITE;

    private static final Path DEFAULT_JSON_FILE = Paths.get("data", "notes.json");
    private static final Path DEFAULT_DB_FILE = Paths.get("data", "second-brain.db");

    private Storages() {
    }

    /** Создаёт хранилище, выбранное переменными окружения. */
    public static NoteRepository fromEnvironment() {
        return create(System.getenv(ENV_KIND), jsonFile(), dbFile());
    }

    /** Путь к файлу JSON с учётом переменных окружения. */
    public static Path jsonFile() {
        return pathFromEnv(ENV_JSON_FILE, DEFAULT_JSON_FILE);
    }

    /** Путь к файлу базы SQLite с учётом переменных окружения. */
    public static Path dbFile() {
        return pathFromEnv(ENV_DB_FILE, DEFAULT_DB_FILE);
    }

    /**
     * Создаёт хранилище указанного вида.
     *
     * @param kind     {@code "json"}, {@code "sqlite"} или {@code null} — тогда берётся
     *                 {@link #DEFAULT_KIND}; регистр и пробелы не важны
     * @param jsonFile путь к файлу JSON
     * @param dbFile   путь к файлу базы SQLite
     * @throws IllegalArgumentException если вид хранилища неизвестен — молча
     *                                  свалиться на другое хранилище было бы хуже:
     *                                  пользователь решил бы, что заметки исчезли
     */
    public static NoteRepository create(String kind, Path jsonFile, Path dbFile) {
        String normalized = (kind == null || kind.isBlank())
                ? DEFAULT_KIND
                : kind.trim().toLowerCase(Locale.ROOT);

        return switch (normalized) {
            case JSON -> new JsonNoteRepository(jsonFile);
            case SQLITE -> sqlite(dbFile);
            default -> throw new IllegalArgumentException(
                    "Неизвестное хранилище: «" + kind + "». Допустимые значения "
                            + ENV_KIND + ": " + JSON + " или " + SQLITE + ".");
        };
    }

    private static NoteRepository sqlite(Path dbFile) {
        Path absolute = dbFile.toAbsolutePath().normalize();
        var dataSource = SqliteFiles.dataSource(absolute);
        SqliteSchema.apply(dataSource);
        return new SqliteNoteRepository(dataSource, absolute.toString());
    }

    /**
     * Замок на досылку в Notion, соответствующий выбранному хранилищу.
     *
     * <p>Для SQLite — межпроцессный: сервер и консоль работают с одним файлом базы,
     * и досылать очередь одновременно им нельзя, иначе в Notion появятся дубликаты
     * страниц. Для JSON — в пределах процесса: там одновременная работа двух
     * процессов и так небезопасна, а сервер по умолчанию работает на SQLite.
     */
    public static SyncLock syncLockFromEnvironment() {
        return createSyncLock(System.getenv(ENV_KIND), dbFile());
    }

    /** @see #syncLockFromEnvironment() */
    public static SyncLock createSyncLock(String kind, Path dbFile) {
        String normalized = (kind == null || kind.isBlank())
                ? DEFAULT_KIND
                : kind.trim().toLowerCase(Locale.ROOT);

        if (!SQLITE.equals(normalized)) {
            return new JvmSyncLock();
        }
        Path absolute = dbFile.toAbsolutePath().normalize();
        var dataSource = SqliteFiles.dataSource(absolute);
        // Схема идемпотентна: вызов безопасен, даже если хранилище её уже создало.
        SqliteSchema.apply(dataSource);
        return new SqliteSyncLock(dataSource);
    }

    private static Path pathFromEnv(String variable, Path fallback) {
        String value = System.getenv(variable);
        return (value == null || value.isBlank()) ? fallback : Paths.get(value.trim());
    }
}
