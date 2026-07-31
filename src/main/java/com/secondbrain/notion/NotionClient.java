package com.secondbrain.notion;

import com.secondbrain.model.Note;

/**
 * Отправка заметки в Notion.
 *
 * <p>Интерфейс намеренно узкий: одна операция — создать страницу.
 * Благодаря этому в тестах вместо реального Notion подставляется заглушка,
 * и поведение при сбое можно проверить, не трогая сеть.
 */
public interface NotionClient {

    /**
     * Создаёт страницу в базе, соответствующей типу заметки.
     *
     * @param note заметка для отправки
     * @return id созданной страницы в Notion
     * @throws NotionException если Notion недоступен, отверг запрос
     *                         или база для этого типа не настроена
     */
    String createPage(Note note) throws NotionException;
}
