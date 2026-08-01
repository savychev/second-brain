package com.secondbrain.telegram;

/**
 * Обращение к Telegram не удалось.
 *
 * <p>Не фатально: цикл опроса переживает такие ошибки и продолжает работу.
 * Связь с Telegram пропадает и восстанавливается, это нормальная жизнь.
 */
public class TelegramException extends RuntimeException {

    public TelegramException(String message) {
        super(message);
    }

    public TelegramException(String message, Throwable cause) {
        super(message, cause);
    }
}
