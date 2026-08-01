package com.secondbrain;

import com.secondbrain.classify.RuleBasedClassifier;
import com.secondbrain.cli.ConsoleApp;
import com.secondbrain.core.CaptureService;
import com.secondbrain.notion.HttpNotionClient;
import com.secondbrain.notion.NotionConfig;
import com.secondbrain.notion.NotionSyncService;
import com.secondbrain.storage.NoteRepository;
import com.secondbrain.storage.Storages;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Точка входа Second Brain.
 *
 * <p>Здесь — и только здесь — детали соединяются друг с другом: хранилище,
 * классификатор, клиент Notion. Сама логика в этих классах, а не тут.
 *
 * <p>Режимы:
 * <ul>
 *   <li>без аргументов — интерактивный REPL;</li>
 *   <li>{@code --stdin} — захватить мысль из stdin (надёжный one-shot в UTF-8);</li>
 *   <li>{@code --sync} — только дослать очередь в Notion и выйти;</li>
 *   <li>с текстом в аргументах — захватить его и выйти.</li>
 * </ul>
 *
 * <p>Хранилище выбирается переменными окружения — см. {@link Storages}.
 */
public final class App {

    private App() {
    }

    public static void main(String[] args) {
        // Гарантируем корректный вывод кириллицы независимо от кодовой страницы консоли.
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);

        NoteRepository repository;
        try {
            repository = Storages.fromEnvironment();
        } catch (RuntimeException e) {
            // Ошибка настройки — не повод показывать пользователю стектрейс.
            // Печатаем через UTF-8 поток, иначе кириллица превратится в «?????».
            out.println("Не удалось открыть хранилище заметок.");
            out.println("  " + e.getMessage());
            System.exit(1);
            return;
        }

        NotionConfig notionConfig = NotionConfig.load();
        NotionSyncService notionSync = new NotionSyncService(
                new HttpNotionClient(notionConfig), repository, notionConfig.isEnabled());

        CaptureService captureService =
                new CaptureService(new RuleBasedClassifier(), repository, notionSync);
        ConsoleApp app = new ConsoleApp(captureService, repository, notionSync, out);

        if (args.length == 1 && args[0].equals("--import-json")) {
            // Перенос всегда идёт из JSON в SQLite, независимо от текущего выбора
            // хранилища: команда для того и существует, чтобы подготовить переключение.
            NoteRepository sqlite = Storages.create(Storages.SQLITE,
                    Storages.jsonFile(), Storages.dbFile());
            boolean ok = app.importJsonToSqlite(Storages.jsonFile(), sqlite);
            System.exit(ok ? 0 : 1);
            return;
        }

        if (args.length == 1 && args[0].equals("--sync")) {
            app.syncOnly();
            return;
        }

        // Досылаем то, что не ушло в прошлые разы (Notion мог быть недоступен).
        notionSync.flushQueue();

        if (args.length == 1 && args[0].equals("--stdin")) {
            // Надёжный one-shot для Windows: текст приходит из stdin в UTF-8,
            // минуя декодирование argv системной кодовой страницей.
            app.captureFromStdin(System.in);
        } else if (args.length > 0) {
            app.captureOnce(String.join(" ", args));
        } else {
            app.repl(System.in);
        }
    }

}
