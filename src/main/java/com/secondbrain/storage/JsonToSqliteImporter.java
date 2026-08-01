package com.secondbrain.storage;

import com.secondbrain.model.Note;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Перенос заметок из файла JSON в другое хранилище.
 *
 * <p>Импорт идемпотентен: его можно запускать сколько угодно раз, лишних записей
 * и лишних страниц в Notion не появится. Исходный файл не изменяется и не удаляется —
 * он остаётся путём отката.
 *
 * <h2>Главная опасность и как она снята</h2>
 *
 * <p>Очередь досылки в Notion определяется ровно одним признаком:
 * {@code notionPageId == null}. Заметка, потерявшая при переносе этот признак,
 * будет считаться неотправленной, и в Notion появится <b>вторая страница</b> той же мысли.
 *
 * <p>Одного {@link NoteRepository#save} для этого недостаточно. В SQLite он написан как
 * {@code ON CONFLICT(id) DO NOTHING} и при повторном импорте отбрасывает входящую строку
 * целиком — вместе с появившимся тем временем id страницы. Реальный сценарий дубля:
 *
 * <ol>
 *   <li>Notion недоступен, заметка захвачена без id страницы;</li>
 *   <li>импорт переносит её в базу — тоже без id;</li>
 *   <li>Notion восстановился, {@code --sync} проставил id <b>в JSON</b>;</li>
 *   <li>повторный импорт: {@code DO NOTHING}, в базе по-прежнему пусто;</li>
 *   <li>переключение на базу — очередь публикует заметку второй раз.</li>
 * </ol>
 *
 * <p>Поэтому для уже отправленных заметок дополнительно вызывается
 * {@link NoteRepository#markSynced}: у него нужная семантика — «проставить, но никогда
 * не перезаписывать существующий id». Закреплено тестами {@code SaveSemanticsTest}
 * и {@code JsonToSqliteImporterTest}.
 */
public final class JsonToSqliteImporter {

    private JsonToSqliteImporter() {
    }

    /**
     * Переносит все заметки из файла JSON в указанное хранилище.
     *
     * @param jsonFile исходный файл; отсутствие файла — не ошибка, а пустой результат
     * @param target   хранилище-получатель
     */
    public static Report importAll(Path jsonFile, NoteRepository target) {
        if (!Files.exists(jsonFile)) {
            return new Report(0, 0, 0, 0, 0, target.findUnsynced().size(), jsonFile);
        }

        List<Note> source = new JsonNoteRepository(jsonFile).findAll();
        long before = target.count();
        int carriedOverPageIds = 0;

        for (Note note : source) {
            // Переносим заметку как есть: тот же id, то же время, те же теги.
            // НЕ через CaptureService и не через Note.create() — иначе заметка
            // получила бы новый id и новое время, то есть стала бы другой заметкой.
            target.save(note);

            if (note.isSynced()) {
                // Обязательный второй шаг — см. описание класса.
                target.markSynced(note.id(), note.notionPageId());
                carriedOverPageIds++;
            }
        }

        long after = target.count();
        int inserted = (int) (after - before);

        // Прямая проверка инварианта: каждая заметка, отправленная в источнике,
        // обязана остаться отправленной у получателя. Считаем именно потери,
        // а не разность счётчиков — в хранилище могут быть и свои заметки,
        // не имеющие отношения к импорту.
        Set<String> pendingInTarget = target.findUnsynced().stream()
                .map(Note::id)
                .collect(Collectors.toSet());
        int lostPageIds = (int) source.stream()
                .filter(Note::isSynced)
                .filter(n -> pendingInTarget.contains(n.id()))
                .count();

        return new Report(
                source.size(),
                inserted,
                source.size() - inserted,
                carriedOverPageIds,
                lostPageIds,
                target.findUnsynced().size(),
                jsonFile);
    }

    /**
     * Итог импорта.
     *
     * @param read              сколько заметок прочитано из файла
     * @param inserted          сколько добавлено в хранилище
     * @param alreadyPresent    сколько уже там было (повторный запуск)
     * @param withNotionPage    сколько из прочитанных уже были отправлены в Notion
     * @param lostPageIds       сколько отправленных заметок ПОТЕРЯЛИ признак отправки
     *                          при переносе — обязано быть 0. Любое другое значение
     *                          означает, что досылка создаст дубликаты страниц
     * @param pendingAfter      сколько всего заметок ждёт отправки в хранилище после импорта
     * @param sourceFile        откуда импортировали
     */
    public record Report(int read,
                         int inserted,
                         int alreadyPresent,
                         int withNotionPage,
                         int lostPageIds,
                         int pendingAfter,
                         Path sourceFile) {

        /**
         * Сошлось ли главное: ни одна отправленная заметка не потеряла признак отправки.
         * Если вернуло {@code false} — переключаться на новое хранилище нельзя.
         */
        public boolean pageIdsPreserved() {
            return lostPageIds == 0;
        }
    }
}
