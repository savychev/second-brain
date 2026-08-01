package com.secondbrain.notion;

import com.secondbrain.model.Note;
import com.secondbrain.storage.NoteRepository;

import java.util.List;

/**
 * Отправка заметок в Notion с локальной очередью досылки.
 *
 * <p>Реализует требование P0 №5 «устойчивость к сбоям»: если Notion недоступен,
 * заметка остаётся помеченной как неотправленная и уходит при следующей
 * успешной попытке. Ошибка отправки никогда не мешает захвату мысли.
 */
public class NotionSyncService {

    private final NotionClient client;
    private final NoteRepository repository;
    private final boolean enabled;

    public NotionSyncService(NotionClient client, NoteRepository repository, boolean enabled) {
        this.client = client;
        this.repository = repository;
        this.enabled = enabled;
    }

    /** Настроена ли отправка в Notion. */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Пытается отправить одну заметку. Не бросает исключений: неудача —
     * штатный сценарий, заметка просто остаётся в очереди.
     */
    public SyncResult trySync(Note note) {
        if (!enabled) {
            return SyncResult.skipped("Notion не настроен");
        }
        String pageId;
        try {
            pageId = client.createPage(note);
        } catch (NotionException e) {
            // Штатный случай: страница не создана, заметка ждёт в очереди.
            return SyncResult.queued(e.getMessage());
        }

        // Страница в Notion уже СУЩЕСТВУЕТ. С этого момента любая ошибка означает,
        // что мы знаем о странице, но не смогли это записать. Если просто дать
        // исключению улететь, заметка останется с пустым notion_page_id и следующая
        // досылка создаст ВТОРУЮ страницу. Поэтому отделяем этот случай явно.
        try {
            repository.markSynced(note.id(), pageId);
        } catch (RuntimeException e) {
            return SyncResult.orphaned(pageId, e.getMessage());
        }
        return SyncResult.sent(pageId);
    }

    /**
     * Досылает все накопившиеся неотправленные заметки.
     *
     * <p>При первой же неудаче останавливается: если Notion недоступен, нет
     * смысла долбить его остальными — они подождут следующего раза.
     *
     * @return сколько заметок удалось отправить
     */
    public int flushQueue() {
        if (!enabled) {
            return 0;
        }
        List<Note> pending = repository.findUnsynced();
        int sent = 0;
        for (Note note : pending) {
            SyncResult result = trySync(note);
            if (!result.isSent()) {
                break;
            }
            sent++;
        }
        return sent;
    }

    /** Сколько заметок ждёт отправки. */
    public int pendingCount() {
        return repository.findUnsynced().size();
    }

    /** Итог попытки отправки. */
    public record SyncResult(Status status, String detail) {

        public enum Status {
            /** Успешно отправлено в Notion и записано локально. */
            SENT,
            /** Не удалось — осталось в локальной очереди. */
            QUEUED,
            /** Отправка не выполнялась: интеграция не настроена. */
            SKIPPED,
            /**
             * Страница в Notion создана, но записать это локально не удалось.
             *
             * <p>Самый неприятный исход: повторная попытка создаст дубликат.
             * Поэтому такой результат не считается успехом, останавливает досылку
             * и громко сообщает id страницы — его можно проставить вручную.
             */
            ORPHANED
        }

        public static SyncResult sent(String pageId) {
            return new SyncResult(Status.SENT, pageId);
        }

        public static SyncResult queued(String reason) {
            return new SyncResult(Status.QUEUED, reason);
        }

        public static SyncResult skipped(String reason) {
            return new SyncResult(Status.SKIPPED, reason);
        }

        public static SyncResult orphaned(String pageId, String reason) {
            return new SyncResult(Status.ORPHANED,
                    "страница создана (" + pageId + "), но локально не отмечена: " + reason);
        }

        public boolean isSent() {
            return status == Status.SENT;
        }
    }
}
