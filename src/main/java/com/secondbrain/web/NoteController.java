package com.secondbrain.web;

import com.secondbrain.core.CaptureService;
import com.secondbrain.model.Note;
import com.secondbrain.model.NoteType;
import com.secondbrain.notion.NotionSyncService;
import com.secondbrain.storage.NoteRepository;
import com.secondbrain.web.dto.CapturedResponse;
import com.secondbrain.web.dto.CreateNoteRequest;
import com.secondbrain.web.dto.NoteResponse;
import com.secondbrain.web.dto.StatsResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;

/**
 * HTTP-вход для заметок.
 *
 * <p>Контроллер намеренно тонкий: он переводит запрос в вызов
 * {@link CaptureService} и обратно, но не содержит логики захвата. Та же логика
 * обслуживает консоль, и раздваивать её нельзя — иначе поведение начнёт
 * расходиться между входами.
 */
@RestController
public class NoteController {

    /** Верхняя граница на размер страницы: защита от запроса «отдай всё». */
    private static final int MAX_PAGE_SIZE = 500;
    private static final int DEFAULT_PAGE_SIZE = 50;

    private final CaptureService captureService;
    private final NoteRepository repository;
    private final NotionSyncService notionSync;

    /**
     * Зависимости приходят через конструктор — Spring подставит бины из
     * {@code AppConfig}. Конструктор, а не поля: так объект нельзя создать
     * недоделанным, и в тестах его легко собрать вручную с заглушками.
     */
    public NoteController(CaptureService captureService,
                          NoteRepository repository,
                          NotionSyncService notionSync) {
        this.captureService = captureService;
        this.repository = repository;
        this.notionSync = notionSync;
    }

    /**
     * Захват мысли.
     *
     * <p>Отвечает {@code 201 Created} <b>даже если Notion недоступен</b> — это
     * требование «ноль потерь», выраженное по-HTTP: мысль сохранена локально,
     * а судьба отправки видна в блоке {@code notion} ответа. Возвращать ошибку
     * значило бы сказать клиенту «не сохранилось», хотя сохранилось.
     */
    @PostMapping("/notes")
    public ResponseEntity<CapturedResponse> capture(@Valid @RequestBody CreateNoteRequest request) {
        CaptureService.Captured captured =
                captureService.capture(request.text().trim(), request.sourceOrDefault());

        URI location = UriComponentsBuilder.fromPath("/notes/{id}")
                .buildAndExpand(captured.note().id())
                .toUri();

        return ResponseEntity.created(location).body(CapturedResponse.from(captured));
    }

    /**
     * Лента заметок, от новых к старым.
     *
     * <p>Пустой результат — это {@code 200} с пустым массивом, а не {@code 404}:
     * лента существует, просто пока пуста.
     *
     * @param type   необязательный фильтр по типу
     * @param limit  размер страницы; значение зажимается в допустимые границы
     * @param offset сколько пропустить
     */
    @GetMapping("/notes")
    public List<NoteResponse> list(@RequestParam(required = false) NoteType type,
                                   @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int limit,
                                   @RequestParam(defaultValue = "0") int offset) {
        // Границы правим молча, а не отвечаем ошибкой: limit=1000 — это явно
        // «дай побольше», а не попытка что-то сломать.
        int safeLimit = Math.clamp(limit, 1, MAX_PAGE_SIZE);
        int safeOffset = Math.max(0, offset);

        if (type == null) {
            return NoteResponse.from(repository.findPage(safeLimit, safeOffset));
        }
        List<Note> ofType = repository.findByType(type);
        return NoteResponse.from(ofType.stream().skip(safeOffset).limit(safeLimit).toList());
    }

    /**
     * Одна заметка по идентификатору.
     *
     * <p>Нужен потому, что {@code POST /notes} возвращает заголовок
     * {@code Location} с этим адресом — без него ссылка вела бы в никуда.
     */
    @GetMapping("/notes/{id}")
    public NoteResponse byId(@PathVariable String id) {
        return repository.findAll().stream()
                .filter(n -> n.id().equals(id))
                .findFirst()
                .map(NoteResponse::from)
                .orElseThrow(() -> new NoteNotFoundException(id));
    }

    /** Сводка по хранилищу — веб-двойник консольной команды {@code :stats}. */
    @GetMapping("/stats")
    public StatsResponse stats() {
        return new StatsResponse(
                repository.count(),
                repository.countByType(),
                repository.describe(),
                notionSync.isEnabled(),
                repository.findUnsynced().size());
    }
}
