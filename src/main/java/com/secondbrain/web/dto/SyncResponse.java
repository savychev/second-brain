package com.secondbrain.web.dto;

/**
 * Итог ручной досылки очереди в Notion — веб-двойник команды {@code :sync}.
 *
 * <p>Отвечает {@code 200} даже при частичной досылке и при выключенной интеграции:
 * недоступен внешний Notion, а не Second Brain. Клиенту важно увидеть, сколько
 * осталось, а не получить ошибку.
 *
 * @param notionEnabled настроена ли интеграция
 * @param pendingBefore сколько ждало отправки до вызова
 * @param sent          сколько удалось отправить
 * @param pendingAfter  сколько осталось ждать
 */
public record SyncResponse(boolean notionEnabled,
                           int pendingBefore,
                           int sent,
                           int pendingAfter) {
}
