package com.secondbrain.telegram;

import com.secondbrain.classify.ClassificationResult;
import com.secondbrain.classify.Classifier;
import com.secondbrain.core.CaptureService;
import com.secondbrain.model.NoteType;
import com.secondbrain.storage.JsonNoteRepository;
import com.secondbrain.storage.NoteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Telegram-бот.
 *
 * <p>Главное здесь — <b>защита от чужих</b>. Это первый вход в систему,
 * открытый наружу: бота можно найти по имени, и написать ему может кто угодно.
 * Всё остальное вторично.
 */
class TelegramBotTest {

    private static final long OWNER = 111L;
    private static final long STRANGER = 999L;

    /** Клиент, записывающий отправленное вместо обращения к сети. */
    private static class RecordingClient extends TelegramClient {
        final List<String> sent = new ArrayList<>();
        final List<Long> recipients = new ArrayList<>();

        RecordingClient(TelegramConfig config) {
            super(config, null);
        }

        @Override
        public void sendMessage(long chatId, String text) {
            recipients.add(chatId);
            sent.add(text);
        }

        @Override
        public List<TelegramMessage> getUpdates(long offset) {
            return List.of();
        }
    }

    @TempDir
    Path dir;

    private RecordingClient client;
    private NoteRepository repository;

    private TelegramBot bot(Set<Long> allowed) {
        TelegramConfig config = new TelegramConfig("test-token", allowed, Duration.ofSeconds(1));
        client = new RecordingClient(config);
        repository = new JsonNoteRepository(dir.resolve("notes.json"));
        Classifier classifier = text -> ClassificationResult.of(
                NoteType.TASK, 0.9, "тест", List.of("дом"));
        CaptureService capture = new CaptureService(classifier, repository);
        return new TelegramBot(config, client, capture);
    }

    private static TelegramClient.TelegramMessage message(long chatId, String text) {
        return new TelegramClient.TelegramMessage(1L, chatId, text, "Тест");
    }

    @BeforeEach
    void reset() {
        client = null;
    }

    // --- безопасность ---

    @Test
    @DisplayName("ГЛАВНОЕ: чужой не может записать мысль в систему")
    void strangerCannotCapture() {
        TelegramBot bot = bot(Set.of(OWNER));

        bot.handle(message(STRANGER, "чужая мысль"));

        assertEquals(0, repository.count(),
                "сообщение из неразрешённого чата НЕ должно попадать в хранилище");
    }

    @Test
    @DisplayName("Пустой список разрешённых — не пускаем НИКОГО")
    void emptyAllowListBlocksEveryone() {
        TelegramBot bot = bot(Set.of());

        bot.handle(message(OWNER, "мысль"));
        bot.handle(message(STRANGER, "мысль"));

        assertEquals(0, repository.count(),
                "пусто не должно означать «всем можно» — иначе бот открыт для любого");
    }

    @Test
    @DisplayName("Незнакомцу сообщаем только его собственный id и ничего больше")
    void strangerLearnsOnlyOwnChatId() {
        TelegramBot bot = bot(Set.of(OWNER));

        bot.handle(message(STRANGER, "кто ты?"));

        assertEquals(1, client.sent.size());
        String reply = client.sent.get(0);
        assertTrue(reply.contains(String.valueOf(STRANGER)),
                "владелец должен узнать свой id из ответа: " + reply);
        assertFalse(reply.contains(String.valueOf(OWNER)),
                "чужой id раскрывать нельзя: " + reply);
        assertFalse(reply.contains("test-token"), "токен не должен утекать: " + reply);
    }

    @Test
    @DisplayName("Ответ уходит тому, кто написал, а не владельцу")
    void replyGoesToSender() {
        TelegramBot bot = bot(Set.of(OWNER));

        bot.handle(message(STRANGER, "привет"));

        assertEquals(List.of(STRANGER), client.recipients);
    }

    // --- захват ---

    @Test
    @DisplayName("Владелец захватывает мысль, источник помечается telegram")
    void ownerCaptures() {
        TelegramBot bot = bot(Set.of(OWNER));

        bot.handle(message(OWNER, "надо купить хлеб"));

        assertEquals(1, repository.count());
        assertEquals("telegram", repository.findAll().get(0).source(),
                "источник нужен, чтобы потом различать, откуда пришла мысль");
        assertEquals(NoteType.TASK, repository.findAll().get(0).type());
    }

    @Test
    @DisplayName("В ответе видно тип, причину и теги — как в консоли")
    void replyShowsClassification() {
        TelegramBot bot = bot(Set.of(OWNER));

        bot.handle(message(OWNER, "надо купить хлеб"));

        String reply = client.sent.get(0);
        assertTrue(reply.contains("TASK"), reply);
        assertTrue(reply.contains("тест"), reply);
        assertTrue(reply.contains("дом"), reply);
    }

    @Test
    @DisplayName("Пустое сообщение не создаёт заметку")
    void blankMessageIsIgnored() {
        TelegramBot bot = bot(Set.of(OWNER));

        bot.handle(message(OWNER, "   "));

        assertEquals(0, repository.count());
        assertEquals(1, client.sent.size(), "но ответить всё же надо");
    }

    @Test
    @DisplayName("Команды не сохраняются как мысли")
    void commandsAreNotCaptured() {
        TelegramBot bot = bot(Set.of(OWNER));

        bot.handle(message(OWNER, "/start"));
        bot.handle(message(OWNER, "/help"));

        assertEquals(0, repository.count(),
                "иначе в системе копились бы заметки «/start»");
        assertEquals(2, client.sent.size());
    }

    @Test
    @DisplayName("Сбой обработки не роняет бота и сообщается пользователю")
    void failureIsReportedNotThrown() {
        TelegramConfig config = new TelegramConfig("t", Set.of(OWNER), Duration.ofSeconds(1));
        client = new RecordingClient(config);
        Classifier broken = text -> {
            throw new IllegalStateException("классификатор сломался");
        };
        CaptureService capture = new CaptureService(broken,
                new JsonNoteRepository(dir.resolve("notes.json")));
        TelegramBot bot = new TelegramBot(config, client, capture);

        // Не должно бросить наружу — иначе цикл опроса умрёт.
        bot.handle(message(OWNER, "мысль"));

        assertEquals(1, client.sent.size());
        assertTrue(client.sent.get(0).contains("сломался"), client.sent.get(0));
    }

    @Test
    @DisplayName("Текст с решёткой сохраняет теги")
    void hashtagsSurvive() {
        TelegramBot bot = bot(Set.of(OWNER));

        bot.handle(message(OWNER, "надо купить хлеб #покупки"));

        assertTrue(repository.findAll().get(0).tags().contains("покупки"));
    }
}
