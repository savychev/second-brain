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
    private final SyncLock lock;

    /**
     * Замок в пределах процесса — достаточно, когда работает только консоль.
     */
    public NotionSyncService(NotionClient client, NoteRepository repository, boolean enabled) {
        this(client, repository, enabled, new JvmSyncLock());
    }

    /**
     * @param lock замок, разрешающий досылку только одному исполнителю.
     *             Когда рядом с консолью работает сервер, здесь обязан быть
     *             межпроцессный {@link SqliteSyncLock} — иначе оба создадут
     *             страницы для одной заметки.
     */
    public NotionSyncService(NotionClient client, NoteRepository repository,
                             boolean enabled, SyncLock lock) {
        this.client = client;
        this.repository = repository;
        this.enabled = enabled;
        this.lock = lock;
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
        if (!lock.tryAcquire()) {
            // Досылкой уже занят кто-то другой. Ждать нельзя: параллельная отправка
            // той же заметки создала бы вторую страницу. Заметка остаётся в очереди.
            return SyncResult.queued("досылка выполняется другим процессом");
        }
        try {
            return sendOne(note);
        } finally {
            lock.release();
        }
    }

    /** Отправка одной заметки. Замок уже должен быть взят вызывающим. */
    private SyncResult sendOne(Note note) {
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
        // Замок берём на всю пачку, а не на каждую заметку: иначе между отправками
        // мог бы вклиниться другой процесс и взять из очереди то же самое.
        if (!lock.tryAcquire()) {
            return 0;
        }
        try {
            List<Note> pending = repository.findUnsynced();
            int sent = 0;
            for (Note note : pending) {
                if (!sendOne(note).isSent()) {
                    break;
                }
                sent++;
            }
            return sent;
        } finally {
            lock.release();
        }
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
