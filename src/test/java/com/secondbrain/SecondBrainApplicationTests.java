package com.secondbrain;

import com.secondbrain.core.CaptureService;
import com.secondbrain.notion.NotionSyncService;
import com.secondbrain.storage.NoteRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Проверка приложения целиком: настоящий контекст Spring, настоящий сервер
 * на случайном порту, настоящие HTTP-запросы.
 *
 * <p>Такой тест ловит то, чего не видят тесты контроллеров: не собрался контекст,
 * не нашёлся бин, не настроилась сериализация JSON, потерялась кодировка.
 * Он же — страховка, что приложение вообще стартует.
 *
 * <p>Запросы шлём {@link HttpClient} из JDK: лишняя зависимость не нужна,
 * а заодно проверяется работа с настоящей сетью, а не с эмуляцией.
 *
 * <p>Хранилище и настройки Notion уведены в {@code target/} через surefire,
 * поэтому тест не трогает ни рабочую базу, ни настоящий Notion.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecondBrainApplicationTests {

    @Autowired
    private NoteRepository repository;

    @Autowired
    private CaptureService captureService;

    @Autowired
    private NotionSyncService notionSyncService;

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> post(String path, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url(path)))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url(path))).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    @Test
    @DisplayName("Контекст поднимается и все детали соединены")
    void contextLoads() {
        assertNotNull(repository, "хранилище должно быть собрано");
        assertNotNull(captureService);
        assertNotNull(notionSyncService);
        assertTrue(port > 0, "сервер должен слушать порт");
    }

    @Test
    @DisplayName("Интеграция с Notion в тестах выключена — страницы создаваться не должны")
    void notionStaysDisabledUnderTests() {
        assertFalse(notionSyncService.isEnabled(),
                "если это упало, тесты способны писать в настоящий Notion — см. surefire в pom.xml");
    }

    @Test
    @DisplayName("Сквозной путь: захват мысли по HTTP и её появление в ленте")
    void capturesThroughRealHttp() throws Exception {
        HttpResponse<String> created = post("/notes", """
                {"text":"надо проверить сквозной путь #тест","source":"api"}
                """);

        assertEquals(201, created.statusCode(), created.body());
        assertTrue(created.body().contains("\"TASK\""), created.body());
        // Кириллица доехала целой через настоящую сеть.
        assertTrue(created.body().contains("надо проверить сквозной путь"), created.body());
        assertTrue(created.body().contains("тест"), "тег должен быть извлечён: " + created.body());
        assertTrue(created.headers().firstValue("Location").isPresent(),
                "ответ обязан указывать, где лежит созданная заметка");

        HttpResponse<String> feed = get("/notes");
        assertEquals(200, feed.statusCode());
        assertTrue(feed.body().contains("надо проверить сквозной путь"));
    }

    @Test
    @DisplayName("Ссылка из Location действительно ведёт к заметке")
    void locationHeaderResolves() throws Exception {
        HttpResponse<String> created = post("/notes", """
                {"text":"мысль для проверки ссылки"}
                """);
        String location = created.headers().firstValue("Location").orElseThrow();

        // Location может быть относительным — берём только путь.
        String path = URI.create(location).getPath();
        HttpResponse<String> fetched = get(path);

        assertEquals(200, fetched.statusCode(), fetched.body());
        assertTrue(fetched.body().contains("мысль для проверки ссылки"));
    }

    @Test
    @DisplayName("Пустой текст отклоняется по-настоящему, а не только в тесте контроллера")
    void rejectsBlankTextOverRealHttp() throws Exception {
        HttpResponse<String> response = post("/notes", "{\"text\":\"\"}");

        assertEquals(400, response.statusCode(), response.body());
        assertTrue(response.body().contains("Некорректный запрос"), response.body());
    }

    @Test
    @DisplayName("Статистика отдаётся и знает про хранилище")
    void statsAreServed() throws Exception {
        HttpResponse<String> stats = get("/stats");

        assertEquals(200, stats.statusCode());
        assertTrue(stats.body().contains("byType"), stats.body());
        assertTrue(stats.body().contains("storage"), stats.body());
    }
}
