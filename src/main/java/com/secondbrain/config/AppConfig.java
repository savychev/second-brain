package com.secondbrain.config;

import com.secondbrain.classify.Classifier;
import com.secondbrain.classify.RuleBasedClassifier;
import com.secondbrain.core.CaptureService;
import com.secondbrain.notion.HttpNotionClient;
import com.secondbrain.notion.NotionClient;
import com.secondbrain.notion.NotionConfig;
import com.secondbrain.notion.NotionSyncService;
import com.secondbrain.storage.NoteRepository;
import com.secondbrain.storage.Storages;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Сборка приложения для режима сервера.
 *
 * <p>Здесь описано, из каких частей состоит система и как они соединяются.
 * Каждый метод с {@code @Bean} — это рецепт создания одной детали; Spring вызывает
 * их сам и подставляет результаты туда, где они нужны (в конструкторы контроллеров
 * и других деталей). Это и называется <b>внедрением зависимостей</b>.
 *
 * <p>Обрати внимание: сами классы —
 * {@link CaptureService}, {@link NotionSyncService}, {@link Storages} —
 * про Spring ничего не знают. В них нет ни одной аннотации фреймворка.
 * Поэтому консольный режим собирает ровно те же детали вручную, без Spring,
 * и работает без запущенного сервера.
 */
@Configuration
public class AppConfig {

    /**
     * Хранилище заметок: JSON или SQLite — выбор по переменным окружения.
     *
     * <p>Возвращается интерфейс, а не конкретный класс: всё, что зависит от
     * хранилища, зависит от {@link NoteRepository} и не знает, какая реализация
     * подставлена. Именно это позволило поменять JSON на SQLite, не трогая
     * логику захвата.
     */
    @Bean
    public NoteRepository noteRepository() {
        return Storages.fromEnvironment();
    }

    /** Настройки Notion: файл {@code notion.properties} или переменные окружения. */
    @Bean
    public NotionConfig notionConfig() {
        return NotionConfig.load();
    }

    @Bean
    public NotionClient notionClient(NotionConfig notionConfig) {
        return new HttpNotionClient(notionConfig);
    }

    /**
     * Очередь досылки в Notion.
     *
     * <p>Аргументы метода Spring подставляет сам, найдя подходящие бины выше.
     */
    @Bean
    public NotionSyncService notionSyncService(NotionClient notionClient,
                                               NoteRepository noteRepository,
                                               NotionConfig notionConfig) {
        return new NotionSyncService(notionClient, noteRepository, notionConfig.isEnabled());
    }

    /**
     * Классификатор мыслей.
     *
     * <p>Здесь тип возвращаемого значения — интерфейс {@link Classifier}, и это
     * не формальность: на этапе 4 достаточно будет вернуть отсюда реализацию
     * на Anthropic API, и всё остальное приложение не заметит подмены.
     */
    @Bean
    public Classifier classifier() {
        return new RuleBasedClassifier();
    }

    /** Захват мысли: классифицировать → теги → сохранить → отправить. */
    @Bean
    public CaptureService captureService(Classifier classifier,
                                         NoteRepository noteRepository,
                                         NotionSyncService notionSyncService) {
        return new CaptureService(classifier, noteRepository, notionSyncService);
    }
}
