package com.secondbrain.cli;

import com.secondbrain.classify.ProviderConfig;

import com.secondbrain.core.CaptureService;
import com.secondbrain.model.Note;
import com.secondbrain.model.NoteType;
import com.secondbrain.notion.NotionSyncService;
import com.secondbrain.storage.JsonToSqliteImporter;
import com.secondbrain.storage.NoteRepository;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Консольный интерфейс: интерактивный ввод мыслей и просмотр истории.
 *
 * <p>Команды REPL:
 * <ul>
 *   <li>любой текст — захватить мысль;</li>
 *   <li>{@code :list [TYPE]} — показать все заметки (или только выбранного типа);</li>
 *   <li>{@code :stats} — счётчики по типам;</li>
 *   <li>{@code :help} — помощь;</li>
 *   <li>{@code :quit} / {@code :exit} — выход.</li>
 * </ul>
 */
public class ConsoleApp {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault());

    private final CaptureService captureService;
    private final NoteRepository repository;
    private final NotionSyncService notionSync;
    private final PrintStream out;

    public ConsoleApp(CaptureService captureService, NoteRepository repository, PrintStream out) {
        this(captureService, repository, null, out);
    }

    public ConsoleApp(CaptureService captureService,
                      NoteRepository repository,
                      NotionSyncService notionSync,
                      PrintStream out) {
        this.captureService = captureService;
        this.repository = repository;
        this.notionSync = notionSync;
        this.out = out;
    }

    /** Захватывает одну мысль и печатает результат (режим одной команды). */
    public void captureOnce(String text) {
        if (text == null || text.isBlank()) {
            out.println("Пустая мысль — нечего сохранять.");
            return;
        }
        CaptureService.Captured c = captureService.capture(text.trim(), "console");
        printCaptured(c);
    }

    /**
     * Переносит заметки из файла JSON в базу SQLite (режим {@code --import-json}).
     *
     * @return {@code true}, если перенос прошёл без потери признака отправки в Notion
     */
    public boolean importJsonToSqlite(java.nio.file.Path jsonFile, NoteRepository target) {
        out.printf("Импорт из %s%n", jsonFile.toAbsolutePath().normalize());
        out.printf("        в %s%n%n", target.describe());

        JsonToSqliteImporter.Report report = JsonToSqliteImporter.importAll(jsonFile, target);

        out.printf("  прочитано:            %d%n", report.read());
        out.printf("  добавлено:            %d%n", report.inserted());
        out.printf("  уже было:             %d%n", report.alreadyPresent());
        out.printf("  из них уже в Notion:  %d%n", report.withNotionPage());
        out.println();

        if (report.pageIdsPreserved()) {
            out.printf("  ✓ Признак отправки сохранён у всех заметок.%n");
            out.printf("  ✓ Ждёт отправки в Notion: %d%n", report.pendingAfter());
            out.println();
            out.println("  Можно переключаться: SECOND_BRAIN_STORAGE=sqlite");
            return true;
        }

        out.printf("  ✗ ОСТАНОВИТЕСЬ: %d заметок потеряли признак отправки в Notion.%n",
                report.lostPageIds());
        out.println("    Переключаться на базу НЕЛЬЗЯ — досылка создаст дубликаты страниц.");
        out.println("    Исходный файл не изменён, ничего не потеряно.");
        return false;
    }

    /** Досылает очередь в Notion и выходит (режим {@code --sync}). */
    public void syncOnly() {
        printSync();
    }

    /** Захватывает одну мысль из всего содержимого stdin (надёжный one-shot в UTF-8). */
    public void captureFromStdin(InputStream in) {
        try {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            // Убираем возможный BOM (PowerShell добавляет его при пайпе в native-процесс).
            if (!text.isEmpty() && text.charAt(0) == '﻿') {
                text = text.substring(1);
            }
            captureOnce(text.trim());
        } catch (Exception e) {
            out.println("Ошибка чтения ввода: " + e.getMessage());
        }
    }

    /** Запускает интерактивный цикл ввода. */
    public void repl(InputStream in) {
        printBanner();
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    prompt();
                    continue;
                }
                if (line.startsWith(":")) {
                    if (!handleCommand(line)) {
                        out.println("До встречи. Голова свободна 🧠");
                        return;
                    }
                } else {
                    printCaptured(captureService.capture(line, "console"));
                }
                prompt();
            }
        } catch (Exception e) {
            out.println("Ошибка ввода: " + e.getMessage());
        }
    }

    /** @return false, если нужно завершить работу. */
    private boolean handleCommand(String line) {
        String[] parts = line.split("\\s+", 2);
        String cmd = parts[0].toLowerCase(Locale.ROOT);
        String arg = parts.length > 1 ? parts[1].trim() : "";
        switch (cmd) {
            case ":quit", ":exit", ":q" -> {
                return false;
            }
            case ":help", ":h" -> printHelp();
            case ":stats" -> printStats();
            case ":sync" -> printSync();
            case ":list", ":ls" -> printList(arg);
            default -> out.println("Неизвестная команда: " + cmd + "  (:help — список команд)");
        }
        return true;
    }

    private void printCaptured(CaptureService.Captured c) {
        Note n = c.note();
        out.printf("  ✓ [%s] сохранено%n", n.type());
        out.printf("    %s%n", c.classification().reason());
        if (!n.tags().isEmpty()) {
            out.printf("    теги: %s%n", String.join(", ", n.tags()));
        }
        switch (c.sync().status()) {
            case SENT -> out.println("    → отправлено в Notion");
            case QUEUED -> out.printf("    → Notion недоступен, ждёт в очереди (%s)%n", c.sync().detail());
            case ORPHANED -> {
                out.println("    ⚠ ВНИМАНИЕ: " + c.sync().detail());
                out.println("      Повторная отправка создаст дубликат страницы.");
            }
            case SKIPPED -> { /* интеграция не настроена — молчим, чтобы не мешать */ }
        }
    }

    private void printList(String arg) {
        List<Note> notes;
        if (arg.isEmpty()) {
            notes = repository.findAll();
        } else {
            NoteType type = parseType(arg);
            if (type == null) {
                out.println("Неизвестный тип: " + arg + "  (IDEA | TASK | LINK | NOTE)");
                return;
            }
            notes = repository.findByType(type);
        }
        if (notes.isEmpty()) {
            out.println("Пока пусто.");
            return;
        }
        for (Note n : notes) {
            out.printf("  %s  %-4s  %s%n", TS.format(n.createdAt()), n.type(), n.text());
        }
        out.printf("  — всего: %d%n", notes.size());
    }

    private void printStats() {
        // Путь показываем всегда: если программу запустили из другого каталога,
        // хранилище окажется пустым, и без пути это выглядит как потеря заметок.
        out.printf("  Хранилище: %s%n", repository.describe());
        out.printf("  Классификация: %s%n", ProviderConfig.load().describe());
        out.printf("  Всего заметок: %d%n", repository.count());
        for (NoteType type : NoteType.values()) {
            out.printf("    %-4s : %d%n", type, repository.findByType(type).size());
        }
        int pending = repository.findUnsynced().size();
        if (notionSync != null && notionSync.isEnabled()) {
            out.printf("  Ждёт отправки в Notion: %d%n", pending);
        }
    }

    /** Досылает накопившуюся очередь в Notion по команде {@code :sync}. */
    private void printSync() {
        if (notionSync == null || !notionSync.isEnabled()) {
            out.println("  Notion не настроен — заметки хранятся только локально.");
            out.println("  См. раздел «Настройка Notion» в README.");
            return;
        }
        int pending = notionSync.pendingCount();
        if (pending == 0) {
            out.println("  Очередь пуста — всё уже в Notion.");
            return;
        }
        out.printf("  Отправляю %d заметок…%n", pending);
        int sent = notionSync.flushQueue();
        if (sent == pending) {
            out.printf("  ✓ Отправлено: %d%n", sent);
        } else {
            out.printf("  Отправлено %d из %d, остальные ждут — Notion недоступен.%n", sent, pending);
        }
    }

    private static NoteType parseType(String s) {
        try {
            return NoteType.valueOf(s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void printBanner() {
        out.println("╔══════════════════════════════════════════════╗");
        out.println("║  Second Brain — точка захвата мыслей          ║");
        out.println("╚══════════════════════════════════════════════╝");
        out.println("Пиши мысль и жми Enter. :help — команды, :quit — выход.");
        prompt();
    }

    private void printHelp() {
        out.println("Команды:");
        out.println("  <текст>        захватить мысль (IDEA/TASK/LINK/NOTE определяется автоматически)");
        out.println("  :list [ТИП]    показать все заметки или только выбранного типа");
        out.println("  :stats         счётчики по типам");
        out.println("  :sync          дослать в Notion всё, что ждёт в очереди");
        out.println("  :help          эта справка");
        out.println("  :quit          выход");
    }

    private void prompt() {
        out.print("› ");
        out.flush();
    }
}
