package com.secondbrain.web;

import com.secondbrain.classify.RuleBasedClassifier;
import com.secondbrain.core.CaptureService;
import com.secondbrain.model.Note;
import com.secondbrain.model.NoteType;
import com.secondbrain.notion.NotionClient;
import com.secondbrain.notion.NotionException;
import com.secondbrain.notion.NotionSyncService;
import com.secondbrain.storage.JsonNoteRepository;
import com.secondbrain.storage.NoteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Веб-слой заметок.
 *
 * <p>Контроллер собирается вручную и проверяется через {@code MockMvc} — настоящие
 * HTTP-запросы к настоящему коду контроллера, но без поднятия сервера и без сети.
 * Хранилище берём реальное (файл во временном каталоге), клиента Notion — заглушкой:
 * тесты не должны создавать страницы в чьём-либо рабочем пространстве.
 */
class NoteControllerTest {

    /** Заглушка Notion: можно «уронить». */
    private static class FakeNotionClient implements NotionClient {
        boolean available = true;
        int created = 0;

        @Override
        public String createPage(Note note) throws NotionException {
            if (!available) {
                throw new NotionException("Notion недоступен (тест)");
            }
            created++;
            return "page-" + note.id();
        }
    }

    @TempDir
    Path dir;

    private MockMvc mvc;
    private NoteRepository repository;
    private FakeNotionClient notionClient;

    @BeforeEach
    void setUp() {
        repository = new JsonNoteRepository(dir.resolve("notes.json"));
        notionClient = new FakeNotionClient();
        NotionSyncService sync = new NotionSyncService(notionClient, repository, true);
        CaptureService capture = new CaptureService(new RuleBasedClassifier(), repository, sync);

        mvc = MockMvcBuilders
                .standaloneSetup(new NoteController(capture, repository, sync))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private void given(String id, String text, NoteType type, Instant at, String pageId) {
        repository.save(new Note(id, text, type, at, List.of(), "console", pageId));
    }

    // --- захват ---

    @Test
    @DisplayName("POST /notes: 201, Location и разобранная классификация")
    void capturesThought() throws Exception {
        mvc.perform(post("/notes")
                        .contentType("application/json")
                        .content("""
                                {"text":"надо купить билеты #поездка","source":"api"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.note.type", is("TASK")))
                .andExpect(jsonPath("$.note.text", is("надо купить билеты #поездка")))
                .andExpect(jsonPath("$.note.tags[0]", is("поездка")))
                .andExpect(jsonPath("$.note.source", is("api")))
                .andExpect(jsonPath("$.note.synced", is(true)))
                .andExpect(jsonPath("$.classification.reason").exists())
                .andExpect(jsonPath("$.notion.status", is("SENT")));
    }

    @Test
    @DisplayName("Notion недоступен → всё равно 201: мысль сохранена, это и есть «ноль потерь»")
    void capturingSucceedsWhenNotionIsDown() throws Exception {
        notionClient.available = false;

        mvc.perform(post("/notes")
                        .contentType("application/json")
                        .content("""
                                {"text":"мысль во время сбоя"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.notion.status", is("QUEUED")))
                .andExpect(jsonPath("$.note.synced", is(false)));

        // Заметка действительно лежит в хранилище и ждёт отправки.
        org.junit.jupiter.api.Assertions.assertEquals(1, repository.count());
        org.junit.jupiter.api.Assertions.assertEquals(1, repository.findUnsynced().size());
    }

    @Test
    @DisplayName("Источник по умолчанию — api")
    void defaultsSourceToApi() throws Exception {
        mvc.perform(post("/notes")
                        .contentType("application/json")
                        .content("""
                                {"text":"мысль без источника"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.note.source", is("api")));
    }

    @Test
    @DisplayName("Клиент не может подделать notionPageId — поля просто нет во входе")
    void clientCannotForgeNotionPageId() throws Exception {
        notionClient.available = false;

        mvc.perform(post("/notes")
                        .contentType("application/json")
                        .content("""
                                {"text":"попытка обмана","notionPageId":"поддельная-страница"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.note.synced", is(false)))
                .andExpect(jsonPath("$.note.notionPageId").doesNotExist());

        org.junit.jupiter.api.Assertions.assertEquals(1, repository.findUnsynced().size(),
                "заметка обязана остаться в очереди — иначе она никогда не попадёт в Notion");
    }

    // --- проверка входных данных ---

    @Test
    @DisplayName("Пустой текст → 400 с понятным объяснением")
    void rejectsBlankText() throws Exception {
        mvc.perform(post("/notes")
                        .contentType("application/json")
                        .content("""
                                {"text":"   "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title", is("Некорректный запрос")))
                .andExpect(jsonPath("$.errors.text").exists());
    }

    @Test
    @DisplayName("Слишком длинный текст → 400, а не молчаливая обрезка")
    void rejectsOverlongText() throws Exception {
        String tooLong = "а".repeat(10_001);

        mvc.perform(post("/notes")
                        .contentType("application/json")
                        .content("{\"text\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.text").exists());
    }

    // --- лента ---

    @Test
    @DisplayName("GET /notes: от новых к старым")
    void listsNewestFirst() throws Exception {
        given("n1", "первая", NoteType.NOTE, Instant.parse("2026-07-27T10:00:00Z"), null);
        given("n2", "вторая", NoteType.NOTE, Instant.parse("2026-07-27T11:00:00Z"), null);

        mvc.perform(get("/notes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].text", is("вторая")))
                .andExpect(jsonPath("$[1].text", is("первая")));
    }

    @Test
    @DisplayName("Пустая лента — 200 и пустой массив, а не 404")
    void emptyFeedIsOkNotFound() throws Exception {
        mvc.perform(get("/notes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("Фильтр по типу")
    void filtersByType() throws Exception {
        given("n1", "задача", NoteType.TASK, Instant.parse("2026-07-27T10:00:00Z"), null);
        given("n2", "идея", NoteType.IDEA, Instant.parse("2026-07-27T11:00:00Z"), null);

        mvc.perform(get("/notes").param("type", "TASK"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].text", is("задача")));
    }

    @Test
    @DisplayName("Постраничный вывод")
    void paginates() throws Exception {
        for (int i = 1; i <= 5; i++) {
            given("n" + i, "мысль " + i, NoteType.NOTE,
                    Instant.parse("2026-07-27T1" + i + ":00:00Z"), null);
        }

        mvc.perform(get("/notes").param("limit", "2").param("offset", "0"))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].text", is("мысль 5")));

        mvc.perform(get("/notes").param("limit", "2").param("offset", "4"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].text", is("мысль 1")));
    }

    @Test
    @DisplayName("Нелепые limit/offset зажимаются, а не роняют запрос")
    void clampsAbsurdPagingValues() throws Exception {
        given("n1", "мысль", NoteType.NOTE, Instant.parse("2026-07-27T10:00:00Z"), null);

        mvc.perform(get("/notes").param("limit", "-5")).andExpect(status().isOk());
        mvc.perform(get("/notes").param("limit", "999999")).andExpect(status().isOk());
        mvc.perform(get("/notes").param("offset", "-10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    @DisplayName("Неизвестный тип в фильтре → 400, а не 500")
    void rejectsUnknownType() throws Exception {
        mvc.perform(get("/notes").param("type", "НЕСУЩЕСТВУЮЩИЙ"))
                .andExpect(status().isBadRequest());
    }

    // --- одна заметка ---

    @Test
    @DisplayName("GET /notes/{id} возвращает заметку")
    void returnsSingleNote() throws Exception {
        given("n1", "мысль", NoteType.NOTE, Instant.parse("2026-07-27T10:00:00Z"), "page-1");

        mvc.perform(get("/notes/n1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is("n1")))
                .andExpect(jsonPath("$.synced", is(true)));
    }

    @Test
    @DisplayName("Несуществующий id → 404 в формате ProblemDetail")
    void unknownIdGives404() throws Exception {
        mvc.perform(get("/notes/нет-такой"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title", is("Заметка не найдена")))
                .andExpect(jsonPath("$.status", is(404)));
    }

    // --- статистика ---

    @Test
    @DisplayName("GET /stats: счётчики, хранилище и длина очереди")
    void reportsStats() throws Exception {
        given("n1", "задача", NoteType.TASK, Instant.parse("2026-07-27T10:00:00Z"), "page-1");
        given("n2", "идея", NoteType.IDEA, Instant.parse("2026-07-27T11:00:00Z"), null);

        mvc.perform(get("/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total", is(2)))
                .andExpect(jsonPath("$.byType.TASK", is(1)))
                .andExpect(jsonPath("$.byType.LINK", is(0)))
                .andExpect(jsonPath("$.notionEnabled", is(true)))
                .andExpect(jsonPath("$.pendingNotionSync", is(1)))
                .andExpect(jsonPath("$.storage").exists());
    }
}
