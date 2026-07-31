package com.secondbrain.notion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.secondbrain.model.Note;
import com.secondbrain.model.NoteType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Проверяет, что тело запроса к Notion собирается по правилам его API,
 * без обращения к сети.
 */
class HttpNotionClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static HttpNotionClient client() {
        Map<NoteType, String> ids = new EnumMap<>(NoteType.class);
        ids.put(NoteType.TASK, "db-task");
        ids.put(NoteType.LINK, "db-link");
        ids.put(NoteType.IDEA, "db-idea");
        ids.put(NoteType.NOTE, "db-note");
        return new HttpNotionClient(new NotionConfig("secret-token", ids));
    }

    private static JsonNode body(Note note, String dbId) throws Exception {
        return MAPPER.readTree(client().buildRequestBody(note, dbId));
    }

    @Test
    @DisplayName("Базовые поля: заголовок, дата, источник, локальный id")
    void buildsCoreProperties() throws Exception {
        Note note = Note.create("купить хлеб", NoteType.TASK, List.of(), "console");
        JsonNode json = body(note, "db-task");

        assertEquals("db-task", json.path("parent").path("database_id").asText());
        JsonNode props = json.path("properties");
        assertEquals("купить хлеб",
                props.path("Мысль").path("title").get(0).path("text").path("content").asText());
        assertEquals(note.createdAt().toString(),
                props.path("Захвачено").path("date").path("start").asText());
        assertEquals("console", props.path("Источник").path("select").path("name").asText());
        assertEquals(note.id(),
                props.path("ID").path("rich_text").get(0).path("text").path("content").asText());
    }

    @Test
    @DisplayName("Теги превращаются в multi_select")
    void buildsTags() throws Exception {
        Note note = Note.create("купить хлеб #дом #срочно", NoteType.TASK,
                List.of("дом", "срочно"), "console");
        JsonNode tags = body(note, "db-task").path("properties").path("Теги").path("multi_select");

        assertEquals(2, tags.size());
        assertEquals("дом", tags.get(0).path("name").asText());
        assertEquals("срочно", tags.get(1).path("name").asText());
    }

    @Test
    @DisplayName("Без тегов свойство не отправляется вовсе")
    void omitsEmptyTags() throws Exception {
        Note note = Note.create("просто мысль", NoteType.NOTE, List.of(), "console");
        assertTrue(body(note, "db-note").path("properties").path("Теги").isMissingNode());
    }

    @Test
    @DisplayName("Для LINK заполняется отдельное поле «Ссылка»")
    void extractsUrlForLinks() throws Exception {
        Note note = Note.create("почитать https://spring.io/guides", NoteType.LINK,
                List.of(), "console");
        assertEquals("https://spring.io/guides",
                body(note, "db-link").path("properties").path("Ссылка").path("url").asText());
    }

    @Test
    @DisplayName("Ссылка без протокола дополняется https://")
    void normalizesBareDomain() throws Exception {
        Note note = Note.create("www.notion.so/api", NoteType.LINK, List.of(), "console");
        assertEquals("https://www.notion.so/api",
                body(note, "db-link").path("properties").path("Ссылка").path("url").asText());
    }

    @Test
    @DisplayName("У не-ссылок поля «Ссылка» нет")
    void noUrlFieldForOtherTypes() throws Exception {
        Note note = Note.create("купить хлеб", NoteType.TASK, List.of(), "console");
        assertTrue(body(note, "db-task").path("properties").path("Ссылка").isMissingNode());
    }

    @Test
    @DisplayName("Короткая мысль не создаёт тело страницы")
    void shortTextHasNoChildren() throws Exception {
        Note note = Note.create("короткая мысль", NoteType.NOTE, List.of(), "console");
        assertTrue(body(note, "db-note").path("children").isMissingNode());
    }

    @Test
    @DisplayName("Длинная мысль: заголовок обрезан, полный текст — в теле страницы")
    void longTextGoesToPageBody() throws Exception {
        String longText = "а".repeat(2500);
        Note note = Note.create(longText, NoteType.NOTE, List.of(), "console");
        JsonNode json = body(note, "db-note");

        String title = json.path("properties").path("Мысль")
                .path("title").get(0).path("text").path("content").asText();
        assertEquals(2000, title.length(), "Notion не принимает фрагменты длиннее 2000 символов");

        JsonNode children = json.path("children");
        assertFalse(children.isMissingNode());
        assertEquals(2, children.size());
        // Текст восстанавливается из кусков без потерь.
        StringBuilder restored = new StringBuilder();
        for (JsonNode block : children) {
            restored.append(block.path("paragraph").path("rich_text")
                    .get(0).path("text").path("content").asText());
        }
        assertEquals(longText, restored.toString());
    }

    @Test
    @DisplayName("Ненастроенная интеграция сообщает об этом понятной ошибкой")
    void failsClearlyWhenNotConfigured() {
        HttpNotionClient notConfigured =
                new HttpNotionClient(new NotionConfig(null, new EnumMap<>(NoteType.class)));
        Note note = Note.create("мысль", NoteType.NOTE, List.of(), "console");

        NotionException e = assertThrows(NotionException.class, () -> notConfigured.createPage(note));
        assertNotNull(e.getMessage());
        assertTrue(e.getMessage().contains("не настроен"), e.getMessage());
    }

    @Test
    @DisplayName("Тип без настроенной базы даёт понятную ошибку")
    void failsWhenDatabaseMissingForType() {
        Map<NoteType, String> onlyTasks = new EnumMap<>(NoteType.class);
        onlyTasks.put(NoteType.TASK, "db-task");
        HttpNotionClient partial = new HttpNotionClient(new NotionConfig("token", onlyTasks));
        Note idea = Note.create("идея", NoteType.IDEA, List.of(), "console");

        NotionException e = assertThrows(NotionException.class, () -> partial.createPage(idea));
        assertTrue(e.getMessage().contains("IDEA"), e.getMessage());
    }
}
