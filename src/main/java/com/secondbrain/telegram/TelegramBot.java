package com.secondbrain.telegram;

import com.secondbrain.core.CaptureService;
import com.secondbrain.model.Note;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Telegram-бот: мобильный вход для захвата мыслей.
 *
 * <p>Тот же {@link CaptureService}, что обслуживает консоль и REST API.
 * Логика захвата не раздваивается — иначе поведение входов со временем
 * начало бы расходиться.
 *
 * <p><b>Проверка разрешения — первое, что происходит с сообщением.</b> Бота
 * можно найти по имени, и без списка разрешённых чатов личная система заметок
 * превратилась бы в открытую форму для кого угодно. Незнакомому чату бот
 * сообщает его идентификатор — так владелец узнаёт, что вписать в настройки, —
 * и больше ничего не делает.
 *
 * <p>Цикл опроса переживает сбои: пропавшая связь, отказ Telegram, ошибка
 * классификации — всё это не должно останавливать бота. Единственное, что
 * его завершает, — явная остановка приложения.
 */
public class TelegramBot {

    private static final Logger log = LoggerFactory.getLogger(TelegramBot.class);

    /** Пауза после сбоя, чтобы не молотить запросами по недоступному сервису. */
    private static final long ERROR_PAUSE_MS = 5_000;

    private final TelegramConfig config;
    private final TelegramClient client;
    private final CaptureService captureService;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread worker;
    private long offset;

    public TelegramBot(TelegramConfig config, CaptureService captureService) {
        this(config, new TelegramClient(config), captureService);
    }

    /** Конструктор для тестов: позволяет подставить свой клиент. */
    public TelegramBot(TelegramConfig config, TelegramClient client, CaptureService captureService) {
        this.config = config;
        this.client = client;
        this.captureService = captureService;
    }

    /** Запускает опрос в отдельном потоке. Без токена не делает ничего. */
    public void start() {
        if (!config.isEnabled()) {
            log.debug("Telegram-бот не настроен, пропускаем");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        worker = new Thread(this::pollLoop, "telegram-bot");
        // Демон: не мешает приложению завершиться.
        worker.setDaemon(true);
        worker.start();
        log.info("Telegram-бот запущен ({})", config.describe());
        if (!config.hasAllowedChats()) {
            log.warn("Ни один чат не разрешён — напиши боту, он подскажет твой идентификатор");
        }
    }

    /** Останавливает опрос. */
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        if (worker != null) {
            worker.interrupt();
        }
        log.info("Telegram-бот остановлен");
    }

    private void pollLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                TelegramClient.Updates updates = client.getUpdates(offset);
                // Сдвигаем указатель ДО обработки и сразу по всей пачке.
                // «До обработки» — иначе сообщение, на котором что-то
                // сломалось, придёт снова и снова. «По всей пачке» — иначе
                // обновление, из которого сообщения не вышло, не подтвердится
                // никогда, и Telegram будет отдавать его без остановки.
                offset = Math.max(offset, updates.nextOffset());
                for (TelegramClient.TelegramMessage message : updates.messages()) {
                    handle(message);
                }
            } catch (TelegramException e) {
                log.debug("Опрос Telegram не удался: {}", e.getMessage());
                pause();
            } catch (RuntimeException e) {
                // Ловим широко: цикл не должен умереть ни от чего.
                log.warn("Непредвиденная ошибка в цикле опроса", e);
                pause();
            }
        }
    }

    /** Обрабатывает одно сообщение. Исключения наружу не выпускает. */
    void handle(TelegramClient.TelegramMessage message) {
        try {
            if (!config.isAllowed(message.chatId())) {
                rejectPolitely(message);
                return;
            }
            String text = message.text() == null ? "" : message.text().trim();
            if (text.isEmpty()) {
                client.sendMessage(message.chatId(), "Пришли текст — я сохраню его как мысль.");
                return;
            }
            if (text.startsWith("/")) {
                handleCommand(message.chatId(), text);
                return;
            }
            capture(message.chatId(), text);
        } catch (RuntimeException e) {
            log.warn("Не удалось обработать сообщение из чата {}", message.chatId(), e);
            trySend(message.chatId(), "Что-то пошло не так: " + e.getMessage());
        }
    }

    /**
     * Отвечает незнакомому чату его идентификатором.
     *
     * <p>Сообщение намеренно не раскрывает ничего о содержимом системы:
     * только собственный идентификатор собеседника, который он и так может
     * узнать десятком способов.
     */
    private void rejectPolitely(TelegramClient.TelegramMessage message) {
        log.info("Сообщение из неразрешённого чата {} ({})", message.chatId(), message.from());
        trySend(message.chatId(), """
                Этот бот личный и принимает мысли только от владельца.

                Если владелец — ты, добавь строку в telegram.properties:
                telegram.allowed.chats=%d

                и перезапусти приложение.""".formatted(message.chatId()));
    }

    private void handleCommand(long chatId, String command) {
        String name = command.split("\\s+", 2)[0].toLowerCase();
        switch (name) {
            case "/start", "/help" -> client.sendMessage(chatId, """
                    Second Brain — точка захвата мыслей.

                    Просто пришли текст, и я определю, что это \
                    (идея, задача, ссылка или заметка), сохраню и отправлю в Notion.

                    Теги можно добавить решёткой: надо купить хлеб #дом""");
            default -> client.sendMessage(chatId,
                    "Не знаю такой команды. Просто пришли мысль текстом.");
        }
    }

    private void capture(long chatId, String text) {
        CaptureService.Captured captured = captureService.capture(text, "telegram");
        client.sendMessage(chatId, reply(captured));
    }

    /** Ответ на захваченную мысль — то же, что видит пользователь в консоли. */
    private static String reply(CaptureService.Captured captured) {
        Note note = captured.note();
        StringBuilder sb = new StringBuilder();
        sb.append("✓ ").append(note.type()).append('\n');
        sb.append(captured.classification().reason());
        if (!note.tags().isEmpty()) {
            sb.append("\nтеги: ").append(String.join(", ", note.tags()));
        }
        switch (captured.sync().status()) {
            case SENT -> sb.append("\n→ отправлено в Notion");
            case QUEUED -> sb.append("\n→ Notion недоступен, ждёт в очереди");
            case ORPHANED -> sb.append("\n⚠ ").append(captured.sync().detail());
            case SKIPPED -> { /* интеграция не настроена — молчим */ }
        }
        return sb.toString();
    }

    /** Отправка, которая не бросает: сообщить о сбое важнее, чем упасть на сбое. */
    private void trySend(long chatId, String text) {
        try {
            client.sendMessage(chatId, text);
        } catch (RuntimeException e) {
            log.debug("Не удалось ответить в чат {}: {}", chatId, e.getMessage());
        }
    }

    private void pause() {
        try {
            Thread.sleep(ERROR_PAUSE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
