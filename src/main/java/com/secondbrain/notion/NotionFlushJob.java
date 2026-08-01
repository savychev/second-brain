package com.secondbrain.notion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Периодическая досылка накопившейся очереди в Notion.
 *
 * <p>Существует только в режиме сервера: консольный запуск разгребает очередь
 * при старте и живёт слишком коротко, чтобы расписание имело смысл.
 *
 * <p>Смысл задачи — в сценарии, ради которого писалась очередь: Notion был
 * недоступен, заметка подождала, и уйти она должна <b>сама</b>, без участия
 * человека. Иначе «ноль потерь» превращается в «ноль потерь, если не забыть
 * нажать кнопку».
 *
 * <p>Параллельного запуска бояться не нужно: {@code @Scheduled} с
 * {@code fixedDelay} отсчитывает паузу от <i>окончания</i> предыдущего запуска,
 * а от других процессов защищает межпроцессный {@link SqliteSyncLock}.
 */
public class NotionFlushJob {

    private static final Logger log = LoggerFactory.getLogger(NotionFlushJob.class);

    private final NotionSyncService notionSync;

    public NotionFlushJob(NotionSyncService notionSync) {
        this.notionSync = notionSync;
    }

    /**
     * Досылает очередь.
     *
     * <p>Интервал настраивается свойством {@code secondbrain.notion.flush-interval-ms}
     * (по умолчанию 5 минут). Первый запуск отложен, чтобы не мешать старту сервера.
     */
    @Scheduled(
            fixedDelayString = "${secondbrain.notion.flush-interval-ms:300000}",
            initialDelayString = "${secondbrain.notion.flush-initial-delay-ms:30000}")
    public void flush() {
        if (!notionSync.isEnabled()) {
            return;
        }
        int pending = notionSync.pendingCount();
        if (pending == 0) {
            return;
        }

        int sent = notionSync.flushQueue();
        if (sent > 0) {
            log.info("Досылка в Notion: отправлено {} из {}", sent, pending);
        } else {
            // Не ошибка: Notion может быть недоступен, или досылкой занят
            // другой процесс. Пишем на уровне debug, чтобы не засорять журнал
            // при длительной недоступности Notion.
            log.debug("Досылка в Notion: ждёт {} заметок, отправить не удалось", pending);
        }
    }
}
