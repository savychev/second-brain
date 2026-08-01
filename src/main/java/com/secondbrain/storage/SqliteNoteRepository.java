package com.secondbrain.storage;

import com.secondbrain.model.Note;
import com.secondbrain.model.NoteType;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Хранилище заметок в SQLite.
 *
 * <p>Единственный класс проекта, содержащий SQL. Всё остальное работает через
 * интерфейс {@link NoteRepository} и о базе не знает.
 *
 * <p>Используется {@code JdbcClient} из Spring JDBC — тонкая обёртка над JDBC:
 * запросы пишутся руками, никакого ORM. Выбор осознанный, обоснование в
 * {@code docs/stage-3-plan.md}. Практическая причина: {@code JdbcClient} создаётся
 * из обычного {@code DataSource} и работает без контекста Spring, поэтому один и тот же
 * класс обслуживает и REST-сервер, и консольный режим.
 *
 * <p>Теги хранятся JSON-массивом в колонке, а не отдельной таблицей. Тогда сохранение
 * заметки остаётся одним INSERT и атомарно само по себе — без транзакции, которой
 * в консольном режиме взяться неоткуда.
 */
public class SqliteNoteRepository implements NoteRepository {

    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<List<String>> TAG_LIST = new TypeReference<>() {
    };

    private static final String COLUMNS =
            "id, text, type, created_at, tags, source, notion_page_id";

    private final JdbcClient jdbc;

    public SqliteNoteRepository(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    @Override
    public void save(Note note) {
        // ON CONFLICT DO NOTHING делает операцию идемпотентной: повторный импорт
        // или повторная попытка сохранения не создают дубликат и не падают.
        jdbc.sql("""
                        INSERT INTO notes (id, text, type, created_at, tags, source, notion_page_id)
                        VALUES (:id, :text, :type, :createdAt, :tags, :source, :notionPageId)
                        ON CONFLICT(id) DO NOTHING
                        """)
                .param("id", note.id())
                .param("text", note.text())
                .param("type", note.type().name())
                .param("createdAt", Timestamps.toText(note.createdAt()))
                .param("tags", writeTags(note.tags()))
                .param("source", note.source())
                // Тип указываем явно: значение бывает null, и без подсказки
                // драйвер вынужден угадывать, какого оно типа.
                .param("notionPageId", blankToNull(note.notionPageId()), Types.VARCHAR)
                .update();
    }

    @Override
    public List<Note> findAll() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM notes ORDER BY created_at DESC")
                .query(MAPPER)
                .list();
    }

    @Override
    public List<Note> findByType(NoteType type) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM notes WHERE type = :type ORDER BY created_at DESC")
                .param("type", type.name())
                .query(MAPPER)
                .list();
    }

    @Override
    public long count() {
        Long count = jdbc.sql("SELECT COUNT(*) FROM notes").query(Long.class).single();
        return count == null ? 0L : count;
    }

    @Override
    public List<Note> findUnsynced() {
        // От старых к новым — чтобы Notion получил заметки в порядке захвата.
        return jdbc.sql("SELECT " + COLUMNS
                        + " FROM notes WHERE notion_page_id IS NULL ORDER BY created_at ASC")
                .query(MAPPER)
                .list();
    }

    @Override
    public void markSynced(String noteId, String notionPageId) {
        // Условие IS NULL — защита от гонки: уже проставленный id страницы
        // перезаписать нельзя, иначе можно потерять ссылку на реальную страницу.
        jdbc.sql("UPDATE notes SET notion_page_id = :pageId WHERE id = :id AND notion_page_id IS NULL")
                .param("pageId", notionPageId)
                .param("id", noteId)
                .update();
    }

    @Override
    public List<Note> findPage(int limit, int offset) {
        return jdbc.sql("SELECT " + COLUMNS
                        + " FROM notes ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
                .param("limit", Math.max(0, limit))
                .param("offset", Math.max(0, offset))
                .query(MAPPER)
                .list();
    }

    @Override
    public Map<NoteType, Long> countByType() {
        Map<NoteType, Long> counts = new EnumMap<>(NoteType.class);
        for (NoteType type : NoteType.values()) {
            counts.put(type, 0L);
        }
        // Один проход по данным вместо одного запроса на каждый тип.
        jdbc.sql("SELECT type, COUNT(*) AS cnt FROM notes GROUP BY type")
                .query((ResultSet rs, int rowNum) -> {
                    counts.put(NoteType.valueOf(rs.getString("type")), rs.getLong("cnt"));
                    return null;
                })
                .list();
        return counts;
    }

    // --- преобразование строки таблицы в заметку ---

    private static final RowMapper<Note> MAPPER = (ResultSet rs, int rowNum) -> new Note(
            rs.getString("id"),
            rs.getString("text"),
            NoteType.valueOf(rs.getString("type")),
            Timestamps.parse(rs.getString("created_at")),
            readTags(rs.getString("tags")),
            rs.getString("source"),
            rs.getString("notion_page_id"));

    private static String writeTags(List<String> tags) {
        return JSON.writeValueAsString(tags == null ? List.of() : tags);
    }

    private static List<String> readTags(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return JSON.readValue(json, TAG_LIST);
    }

    /**
     * Пустую строку приводим к {@code null}.
     *
     * <p>Иначе получилось бы расхождение: {@link Note#isSynced()} считает пустую строку
     * «не отправлено», а SQL-условие {@code notion_page_id IS NULL} — «отправлено».
     * Заметка с пустой строкой навсегда выпала бы из очереди досылки.
     */
    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
