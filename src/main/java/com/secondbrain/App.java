package com.secondbrain;

import com.secondbrain.classify.RuleBasedClassifier;
import com.secondbrain.cli.ConsoleApp;
import com.secondbrain.core.CaptureService;
import com.secondbrain.storage.JsonNoteRepository;
import com.secondbrain.storage.NoteRepository;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Точка входа Second Brain (этап 1 — консольное ядро).
 *
 * <p>Режимы:
 * <ul>
 *   <li>без аргументов — интерактивный REPL;</li>
 *   <li>с аргументами — захватить одну мысль из аргументов и выйти
 *       (удобно для быстрого «сброса» мысли за секунды).</li>
 * </ul>
 *
 * <p>Путь к хранилищу берётся из переменной окружения {@code SECOND_BRAIN_DATA}
 * (по умолчанию {@code ./data/notes.json}).
 */
public final class App {

    private App() {
    }

    public static void main(String[] args) {
        // Гарантируем корректный вывод кириллицы независимо от кодовой страницы консоли.
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);

        NoteRepository repository = new JsonNoteRepository(dataFile());
        CaptureService captureService = new CaptureService(new RuleBasedClassifier(), repository);
        ConsoleApp app = new ConsoleApp(captureService, repository, out);

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

    private static Path dataFile() {
        String custom = System.getenv("SECOND_BRAIN_DATA");
        if (custom != null && !custom.isBlank()) {
            return Paths.get(custom);
        }
        return Paths.get("data", "notes.json");
    }
}
