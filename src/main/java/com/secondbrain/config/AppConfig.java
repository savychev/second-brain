package com.secondbrain.config;

import com.secondbrain.classify.AnthropicConfig;
import com.secondbrain.classify.Classifier;
import com.secondbrain.classify.Classifiers;
import com.secondbrain.core.CaptureService;
import com.secondbrain.notion.HttpNotionClient;
import com.secondbrain.notion.NotionClient;
import com.secondbrain.notion.NotionConfig;
import com.secondbrain.notion.NotionFlushJob;
import com.secondbrain.notion.NotionSyncService;
import com.secondbrain.notion.SyncLock;
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
    /**
     * Замок на досылку, общий для сервера и консоли.
     *
     * <p>Без него фоновая задача сервера и запущенная в тот же момент консоль
     * могли бы взять из очереди одну заметку и создать в Notion две страницы.
     */
    @Bean
    public SyncLock syncLock() {
        return Storages.syncLockFromEnvironment();
    }

    @Bean
    public NotionSyncService notionSyncService(NotionClient notionClient,
                                               NoteRepository noteRepository,
                                               NotionConfig notionConfig,
                                               SyncLock syncLock) {
        return new NotionSyncService(notionClient, noteRepository,
                notionConfig.isEnabled(), syncLock);
    }

    /**
     * Периодическая досылка. Бин объявлен здесь, а не аннотацией на самом классе,
     * чтобы {@link NotionFlushJob} оставался обычным классом без привязки к Spring —
     * как и остальная логика проекта.
     */
    @Bean
    public NotionFlushJob notionFlushJob(NotionSyncService notionSyncService) {
        return new NotionFlushJob(notionSyncService);
    }

    /** Настройки умной классификации: ключ Anthropic, модель, таймаут. */
    @Bean
    public AnthropicConfig anthropicConfig() {
        return AnthropicConfig.load();
    }

    /**
     * Классификатор мыслей.
     *
     * <p>Обещание, данное на этапе 1, сработало буквально: тип возвращаемого
     * значения — интерфейс {@link Classifier}, и подмена правил на модель
     * не потребовала изменений ни в одном классе, который им пользуется.
     *
     * <p>Без ключа Anthropic возвращаются правила — приложение работает как прежде.
     */
    @Bean
    public Classifier classifier(AnthropicConfig anthropicConfig) {
        return Classifiers.create(anthropicConfig);
    }

    /** Захват мысли: классифицировать → теги → сохранить → отправить. */
    @Bean
    public CaptureService captureService(Classifier classifier,
                                         NoteRepository noteRepository,
                                         NotionSyncService notionSyncService) {
        return new CaptureService(classifier, noteRepository, notionSyncService);
    }
}
