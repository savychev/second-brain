package com.secondbrain.web.dto;

import com.secondbrain.model.NoteType;

import java.util.Map;

/**
 * Сводка по хранилищу — веб-двойник консольной команды {@code :stats}.
 *
 * @param total             всего заметок
 * @param byType            счётчики по типам, включая нулевые
 * @param storage           где лежат заметки — помогает понять, что смотришь в нужное место
 * @param notionEnabled     настроена ли интеграция с Notion
 * @param pendingNotionSync сколько заметок ждёт отправки
 */
public record StatsResponse(long total,
                            Map<NoteType, Long> byType,
                            String storage,
                            boolean notionEnabled,
                            int pendingNotionSync) {
}
