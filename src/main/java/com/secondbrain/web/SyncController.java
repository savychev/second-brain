package com.secondbrain.web;

import com.secondbrain.notion.NotionSyncService;
import com.secondbrain.web.dto.SyncResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ручная досылка накопившейся очереди в Notion — веб-двойник {@code :sync}.
 */
@RestController
public class SyncController {

    private final NotionSyncService notionSync;

    public SyncController(NotionSyncService notionSync) {
        this.notionSync = notionSync;
    }

    /**
     * Досылает всё, что ждёт отправки.
     *
     * <p>Метод {@code POST}, а не {@code GET}, потому что вызов меняет состояние
     * (создаёт страницы в Notion). Отвечает {@code 200} и при частичной досылке,
     * и при выключенной интеграции: сломан внешний Notion, а не Second Brain,
     * и клиенту полезнее увидеть цифры, чем получить ошибку.
     */
    @PostMapping("/sync")
    public SyncResponse sync() {
        if (!notionSync.isEnabled()) {
            return new SyncResponse(false, 0, 0, 0);
        }
        int before = notionSync.pendingCount();
        int sent = notionSync.flushQueue();
        return new SyncResponse(true, before, sent, notionSync.pendingCount());
    }
}
