package com.secondbrain.classify;

import com.secondbrain.model.NoteType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Классификатор на локальной модели.
 *
 * <p>Сеть не трогаем: HTTP-клиент подменён заглушкой, которая отдаёт заранее
 * заготовленные ответы. Проверяем разбор ответа и — что важнее — понятность
 * сообщений в тех случаях, где пользователь реально спотыкается:
 * служба не запущена и модель не скачана.
 */
class OllamaClassifierTest {

    /** HTTP-клиент, отдающий заданный ответ или бросающий заданный сбой. */
    private static HttpClient stub(int status, String body, IOException failure) {
        return new HttpClient() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> HttpResponse<T> send(HttpRequest request,
                                            HttpResponse.BodyHandler<T> handler) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                return (HttpResponse<T>) new StubResponse(status, body);
            }

            @Override
            public <T> java.util.concurrent.CompletableFuture<HttpResponse<T>> sendAsync(
                    HttpRequest r, HttpResponse.BodyHandler<T> h) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <T> java.util.concurrent.CompletableFuture<HttpResponse<T>> sendAsync(
                    HttpRequest r, HttpResponse.BodyHandler<T> h,
                    HttpResponse.PushPromiseHandler<T> p) {
                throw new UnsupportedOperationException();
            }

            @Override
            public java.util.Optional<java.net.CookieHandler> cookieHandler() {
                return java.util.Optional.empty();
            }

            @Override
            public java.util.Optional<Duration> connectTimeout() {
                return java.util.Optional.empty();
            }

            @Override
            public Redirect followRedirects() {
                return Redirect.NEVER;
            }

            @Override
            public java.util.Optional<java.net.ProxySelector> proxy() {
                return java.util.Optional.empty();
            }

            @Override
            public javax.net.ssl.SSLContext sslContext() {
                throw new UnsupportedOperationException();
            }

            @Override
            public javax.net.ssl.SSLParameters sslParameters() {
                throw new UnsupportedOperationException();
            }

            @Override
            public java.util.Optional<java.net.Authenticator> authenticator() {
                return java.util.Optional.empty();
            }

            @Override
            public Version version() {
                return Version.HTTP_1_1;
            }

            @Override
            public java.util.Optional<java.util.concurrent.Executor> executor() {
                return java.util.Optional.empty();
            }
        };
    }

    private record StubResponse(int status, String body) implements HttpResponse<String> {
        @Override
        public int statusCode() {
            return status;
        }

        @Override
        public HttpRequest request() {
            return null;
        }

        @Override
        public java.util.Optional<HttpResponse<String>> previousResponse() {
            return java.util.Optional.empty();
        }

        @Override
        public java.net.http.HttpHeaders headers() {
            return java.net.http.HttpHeaders.of(java.util.Map.of(), (a, b) -> true);
        }

        @Override
        public String body() {
            return body;
        }

        @Override
        public java.util.Optional<javax.net.ssl.SSLSession> sslSession() {
            return java.util.Optional.empty();
        }

        @Override
        public java.net.URI uri() {
            return java.net.URI.create("http://localhost:11434/api/chat");
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }

    private static ProviderConfig config() {
        return new ProviderConfig(ProviderConfig.OLLAMA, null,
                "qwen3:4b-instruct", Duration.ofSeconds(8), null);
    }

    private static OllamaClassifier classifier(int status, String body) {
        return new OllamaClassifier(config(), stub(status, body, null));
    }

    @Test
    @DisplayName("Разбирает ответ модели: тип, уверенность, причина, теги")
    void parsesModelAnswer() {
        String body = """
                {"message":{"role":"assistant","content":"{\\"type\\":\\"TASK\\",\\"confidence\\":0.92,\\"reason\\":\\"бытовое дело\\",\\"tags\\":[\\"дом\\",\\"быт\\"]}"},"done":true}
                """;

        ClassificationResult result = classifier(200, body).classify("заменить лампочку");

        assertEquals(NoteType.TASK, result.type());
        assertEquals(0.92, result.confidence());
        assertEquals("бытовое дело", result.reason());
        assertEquals(List.of("дом", "быт"), result.tags());
    }

    @Test
    @DisplayName("Служба не запущена → понятное сообщение, а не «сеть недоступна»")
    void explainsWhenOllamaIsNotRunning() {
        OllamaClassifier c = new OllamaClassifier(config(),
                stub(0, null, new ConnectException("Connection refused")));

        ClassificationFailedException e = assertThrows(ClassificationFailedException.class,
                () -> c.classify("мысль"));

        assertTrue(e.getMessage().contains("запущена ли она"),
                "пользователь должен понять, что чинить: " + e.getMessage());
    }

    @Test
    @DisplayName("Модель не скачана → подсказываем команду ollama pull")
    void explainsWhenModelIsMissing() {
        String body = "{\"error\":\"model 'qwen3:4b-instruct' not found\"}";

        ClassificationFailedException e = assertThrows(ClassificationFailedException.class,
                () -> classifier(404, body).classify("мысль"));

        assertTrue(e.getMessage().contains("ollama pull qwen3:4b-instruct"),
                "в сообщении должна быть готовая команда: " + e.getMessage());
    }

    @Test
    @DisplayName("Мусор вместо ответа → внятная ошибка, подстраховка сработает")
    void failsClearlyOnGarbage() {
        assertThrows(ClassificationFailedException.class,
                () -> classifier(200, "не json вовсе").classify("мысль"));
    }

    @Test
    @DisplayName("Неизвестный тип в ответе не проходит молча")
    void rejectsUnknownType() {
        String body = """
                {"message":{"content":"{\\"type\\":\\"ПОКУПКА\\",\\"confidence\\":0.9,\\"reason\\":\\"x\\",\\"tags\\":[]}"}}
                """;

        ClassificationFailedException e = assertThrows(ClassificationFailedException.class,
                () -> classifier(200, body).classify("мысль"));

        assertTrue(e.getMessage().contains("ПОКУПКА"), e.getMessage());
    }

    @Test
    @DisplayName("Пустая мысль обрабатывается без обращения к модели")
    void blankTextSkipsTheModel() {
        // Заглушка бросила бы исключение, если бы запрос всё-таки ушёл.
        OllamaClassifier c = new OllamaClassifier(config(),
                stub(0, null, new IOException("сюда попадать нельзя")));

        assertEquals(NoteType.NOTE, c.classify("   ").type());
    }

    @Test
    @DisplayName("Больше трёх тегов не берём — договорённость из промпта")
    void keepsAtMostThreeTags() {
        String body = """
                {"message":{"content":"{\\"type\\":\\"NOTE\\",\\"confidence\\":0.5,\\"reason\\":\\"x\\",\\"tags\\":[\\"а\\",\\"б\\",\\"в\\",\\"г\\",\\"д\\"]}"}}
                """;

        assertEquals(3, classifier(200, body).classify("мысль").tags().size());
    }
}
