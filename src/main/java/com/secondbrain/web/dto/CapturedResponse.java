package com.secondbrain.web.dto;

import com.secondbrain.classify.ClassificationResult;
import com.secondbrain.core.CaptureService;
import com.secondbrain.notion.NotionSyncService;

/**
 * Ответ на захват мысли: что сохранили, как классифицировали и что стало с Notion.
 *
 * <p>Три блока вместо одного — чтобы клиенту было видно, почему заметка получила
 * такой тип, и не потерялась ли она по дороге в Notion.
 */
public record CapturedResponse(NoteResponse note,
                               ClassificationView classification,
                               NotionView notion) {

    public static CapturedResponse from(CaptureService.Captured captured) {
        return new CapturedResponse(
                NoteResponse.from(captured.note()),
                ClassificationView.from(captured.classification()),
                NotionView.from(captured.sync()));
    }

    /**
     * Почему заметке присвоен такой тип.
     *
     * @param confidence уверенность классификатора, 0..1
     * @param reason     человекочитаемое объяснение
     */
    public record ClassificationView(double confidence, String reason) {
        static ClassificationView from(ClassificationResult result) {
            return new ClassificationView(result.confidence(), result.reason());
        }
    }

    /**
     * Что стало с отправкой в Notion.
     *
     * <p>Статус {@code QUEUED} — не ошибка: заметка сохранена локально и уйдёт позже.
     * Именно поэтому ответ на захват остаётся успешным даже при недоступном Notion.
     *
     * @param status SENT | QUEUED | SKIPPED | ORPHANED
     * @param detail id страницы при успехе, иначе причина
     */
    public record NotionView(String status, String detail) {
        static NotionView from(NotionSyncService.SyncResult result) {
            return new NotionView(result.status().name(), result.detail());
        }
    }
}
