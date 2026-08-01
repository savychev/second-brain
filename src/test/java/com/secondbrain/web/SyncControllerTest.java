package com.secondbrain.web;

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

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Ручная досылка очереди через HTTP. */
class SyncControllerTest {

    private static class FakeNotionClient implements NotionClient {
        boolean available = true;

        @Override
        public String createPage(Note note) throws NotionException {
            if (!available) {
                throw new NotionException("Notion недоступен (тест)");
            }
            return "page-" + note.id();
        }
    }

    @TempDir
    Path dir;

    private NoteRepository repository;
    private FakeNotionClient client;

    @BeforeEach
    void setUp() {
        repository = new JsonNoteRepository(dir.resolve("notes.json"));
        client = new FakeNotionClient();
    }

    private MockMvc mvcWith(boolean notionEnabled) {
        NotionSyncService sync = new NotionSyncService(client, repository, notionEnabled);
        return MockMvcBuilders.standaloneSetup(new SyncController(sync))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private void pending(String id) {
        repository.save(new Note(id, "мысль " + id, NoteType.NOTE,
                Instant.parse("2026-07-27T10:00:00Z"), List.of(), "console", null));
    }

    @Test
    @DisplayName("POST /sync досылает очередь и отчитывается цифрами")
    void flushesQueue() throws Exception {
        pending("n1");
        pending("n2");

        mvcWith(true).perform(post("/sync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notionEnabled", is(true)))
                .andExpect(jsonPath("$.pendingBefore", is(2)))
                .andExpect(jsonPath("$.sent", is(2)))
                .andExpect(jsonPath("$.pendingAfter", is(0)));
    }

    @Test
    @DisplayName("Notion недоступен → 200 с честными цифрами, а не ошибка")
    void reportsFailureAsNumbersNotError() throws Exception {
        pending("n1");
        client.available = false;

        mvcWith(true).perform(post("/sync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sent", is(0)))
                .andExpect(jsonPath("$.pendingAfter", is(1)));
    }

    @Test
    @DisplayName("Интеграция выключена → 200 и notionEnabled=false")
    void reportsDisabledIntegration() throws Exception {
        pending("n1");

        mvcWith(false).perform(post("/sync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notionEnabled", is(false)))
                .andExpect(jsonPath("$.sent", is(0)));
    }

    @Test
    @DisplayName("Пустая очередь — тоже успех")
    void emptyQueueIsFine() throws Exception {
        mvcWith(true).perform(post("/sync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingBefore", is(0)))
                .andExpect(jsonPath("$.sent", is(0)));
    }
}
