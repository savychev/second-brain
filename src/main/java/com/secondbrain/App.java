package com.secondbrain;

import com.secondbrain.classify.Classifier;
import com.secondbrain.classify.Classifiers;
import com.secondbrain.cli.ConsoleApp;
import com.secondbrain.core.CaptureService;
import com.secondbrain.notion.HttpNotionClient;
import com.secondbrain.notion.NotionConfig;
import com.secondbrain.notion.NotionSyncService;
import com.secondbrain.storage.NoteRepository;
import com.secondbrain.storage.Storages;
import org.springframework.boot.SpringApplication;

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
 *   <li>{@code --import-json} — перенести заметки из JSON в SQLite;</li>
 *   <li>{@code --serve} — поднять REST API на 127.0.0.1:8080;</li>
 *   <li>с текстом в аргументах — захватить его и выйти.</li>
 * </ul>
 *
 * <p>Хранилище выбирается переменными окружения — см. {@link Storages}.
 */
public final class App {

    private App() {
    }

    /**
     * Самая глубокая причина в цепочке — именно она объясняет, что чинить.
     * Spring оборачивает исходное исключение в два-три своих.
     */
    private static Throwable rootCause(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    public static void main(String[] args) {
        // Гарантируем корректный вывод кириллицы независимо от кодовой страницы консоли.
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);

        if (args.length > 0 && args[0].equals("--serve")) {
            // Единственная ветка, поднимающая Spring. Всё остальное работает
            // без него, поэтому консольный захват стартует за доли секунды
            // и не зависит от запущенного сервера.
            out.println("Second Brain — запуск сервера на http://127.0.0.1:8080");
            try {
                SpringApplication.run(SecondBrainApplication.class, args);
            } catch (RuntimeException e) {
                // Ошибка настройки — кривой токен, недоступное хранилище —
                // выпадает стеной Spring-трассы, где нужная строка теряется
                // среди служебных. Повторяем первопричину последней строкой,
                // чтобы человек увидел именно её.
                out.println();
                out.println("Сервер не запустился.");
                out.println("  " + rootCause(e).getMessage());
                System.exit(1);
            }
            return;
        }

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
        // Замок берём межпроцессный: рядом может работать сервер со своей
        // фоновой досылкой, и одновременная отправка создала бы в Notion
        // две страницы одной мысли.
        NotionSyncService notionSync = new NotionSyncService(
                new HttpNotionClient(notionConfig), repository, notionConfig.isEnabled(),
                Storages.syncLockFromEnvironment());

        Classifier classifier;
        try {
            classifier = Classifiers.fromEnvironment();
        } catch (RuntimeException e) {
            // Та же логика, что и для хранилища: ошибка настройки должна
            // читаться, а не выпадать стектрейсом с испорченной кириллицей.
            out.println("Не удалось настроить классификацию.");
            out.println("  " + e.getMessage());
            System.exit(1);
            return;
        }

        CaptureService captureService = new CaptureService(classifier, repository, notionSync);
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
