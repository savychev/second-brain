package com.secondbrain;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Точка входа режима сервера ({@code --serve}).
 *
 * <p>Аннотация {@code @SpringBootApplication} включает три вещи сразу:
 * <ul>
 *   <li>поиск компонентов в этом пакете и вложенных — так находятся
 *       {@code AppConfig} и контроллеры;</li>
 *   <li>автонастройку: увидев в проекте веб-стартер, Spring Boot сам поднимает
 *       Tomcat и настраивает обработку JSON;</li>
 *   <li>возможность объявлять здесь свои настройки.</li>
 * </ul>
 *
 * <p>{@code @EnableScheduling} включает выполнение задач по расписанию — этим
 * пользуется фоновая досылка очереди в Notion. В консольном режиме её нет:
 * запуск слишком короткий, очередь там разгребается при старте.
 *
 * <p>Класс намеренно пустой: вся сборка описана в {@code config/AppConfig},
 * а логика — в обычных классах, ничего не знающих про Spring.
 */
@SpringBootApplication
@EnableScheduling
public class SecondBrainApplication {
}
