package com.secondbrain.notion;

/**
 * Отправка в Notion не удалась.
 *
 * <p>Не является фатальной: заметка уже сохранена локально и остаётся
 * в очереди на досылку.
 */
public class NotionException extends Exception {

    public NotionException(String message) {
        super(message);
    }

    public NotionException(String message, Throwable cause) {
        super(message, cause);
    }
}
