package com.secondbrain.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Титульная информация для Swagger UI и файла OpenAPI.
 *
 * <p>Сами точки входа описывать не нужно — springdoc читает аннотации
 * контроллеров и строит описание сам, поэтому документация не может
 * разойтись с кодом.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI secondBrainOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Second Brain API")
                .version("0.1.0")
                .description("""
                        Единая точка захвата мыслей: одна строка текста — и система сама \
                        определяет, что это (идея, задача, ссылка или заметка), сохраняет \
                        локально и отправляет в нужную базу Notion.

                        Ключевое свойство — «ноль потерь»: заметка сохраняется локально \
                        ДО попытки отправки, поэтому `POST /notes` отвечает `201` даже \
                        при недоступном Notion. Судьба отправки видна в блоке `notion` \
                        ответа, а неотправленные заметки ждут в очереди и уходят позже.
                        """)
                .license(new License().name("MIT").url("https://opensource.org/licenses/MIT")));
    }
}
