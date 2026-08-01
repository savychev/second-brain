package com.secondbrain.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Единое место, где ошибки превращаются в ответы API.
 *
 * <p>Формат ответа — {@code ProblemDetail} (стандарт RFC 9457): готовый тип
 * из Spring, свой изобретать не нужно. Клиент получает предсказанную структуру
 * с полями {@code title}, {@code status}, {@code detail}.
 *
 * <p><b>Тонкость, из-за которой обработчик легко сделать мёртвым.</b>
 * Ошибки проверки входных данных Spring Boot обрабатывает сам, и у его
 * обработчика высокий приоритет. Если объявить здесь ещё один
 * {@code @ExceptionHandler} на тот же тип исключения, он просто никогда не
 * вызовется — а выглядеть будет как рабочий код. Поэтому класс наследует
 * {@link ResponseEntityExceptionHandler} и <b>переопределяет</b> его метод
 * {@link #handleMethodArgumentNotValid}, вставая на место штатного обработчика,
 * а не рядом с ним.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    /** Запрошенной заметки нет. */
    @ExceptionHandler(NoteNotFoundException.class)
    public ProblemDetail handleNoteNotFound(NoteNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Заметка не найдена");
        problem.setDetail(e.getMessage());
        problem.setProperty("noteId", e.noteId());
        return problem;
    }

    /**
     * Хранилище недоступно или повреждено.
     *
     * <p>Отвечаем {@code 503}, а не {@code 500}: это временная неисправность
     * (например, база занята другим процессом), и повтор запроса имеет смысл.
     */
    @ExceptionHandler({IllegalStateException.class, org.springframework.dao.DataAccessException.class})
    public ProblemDetail handleStorageFailure(RuntimeException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);
        problem.setTitle("Хранилище недоступно");
        problem.setDetail(e.getMessage());
        return problem;
    }

    /**
     * Тело запроса не прошло проверку: переопределяем штатный обработчик,
     * чтобы добавить разбор по полям.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        Map<String, String> byField = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(error -> byField.put(error.getField(), error.getDefaultMessage()));

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Некорректный запрос");
        problem.setDetail(byField.isEmpty()
                ? "Тело запроса не прошло проверку"
                : String.join("; ", byField.values()));
        problem.setProperty("errors", byField);

        return ResponseEntity.badRequest().body(problem);
    }
}
