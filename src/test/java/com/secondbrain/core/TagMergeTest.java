package com.secondbrain.core;

import com.secondbrain.classify.ClassificationResult;
import com.secondbrain.classify.Classifier;
import com.secondbrain.model.NoteType;
import com.secondbrain.storage.JsonNoteRepository;
import com.secondbrain.storage.NoteRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Объединение тегов: явные {@code #хештеги} пользователя плюс предложения модели.
 */
class TagMergeTest {

    private static Classifier suggesting(List<String> tags) {
        return text -> ClassificationResult.of(NoteType.TASK, 0.9, "тест", tags);
    }

    private CaptureService service(Path dir, Classifier classifier) {
        NoteRepository repo = new JsonNoteRepository(dir.resolve("notes.json"));
        return new CaptureService(classifier, repo);
    }

    @Test
    @DisplayName("Теги пользователя идут первыми и не теряются")
    void userTagsComeFirstAndSurvive(@TempDir Path dir) {
        CaptureService service = service(dir, suggesting(List.of("покупки", "еда")));

        CaptureService.Captured captured = service.capture("надо купить хлеб #дом", "console");

        assertEquals(List.of("дом", "покупки", "еда"), captured.note().tags(),
                "явно написанный человеком тег обязан остаться и идти первым");
    }

    @Test
    @DisplayName("Повторы не дублируются")
    void doesNotDuplicate(@TempDir Path dir) {
        CaptureService service = service(dir, suggesting(List.of("дом", "покупки")));

        CaptureService.Captured captured = service.capture("надо купить хлеб #дом", "console");

        assertEquals(List.of("дом", "покупки"), captured.note().tags());
    }

    @Test
    @DisplayName("Без хештегов остаются только предложения модели")
    void classifierTagsOnly(@TempDir Path dir) {
        CaptureService service = service(dir, suggesting(List.of("здоровье")));

        CaptureService.Captured captured = service.capture("записаться на приём", "console");

        assertEquals(List.of("здоровье"), captured.note().tags());
    }

    @Test
    @DisplayName("Классификатор без предложений — поведение как до этапа 4")
    void noSuggestionsKeepsHashtagsOnly(@TempDir Path dir) {
        CaptureService service = service(dir, suggesting(List.of()));

        CaptureService.Captured captured = service.capture("мысль #работа #срочно", "console");

        assertEquals(List.of("работа", "срочно"), captured.note().tags());
    }
}
