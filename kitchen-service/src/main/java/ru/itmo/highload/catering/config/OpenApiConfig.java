package ru.itmo.highload.catering.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI corporateCateringOpenApi() {
        return new OpenAPI()
                .servers(java.util.List.of(new io.swagger.v3.oas.models.servers.Server().url("/")))
                .info(new Info()
                        .title("Corporate Catering API")
                        .description("Очередь кухни, приготовление и выдача заказов")
                        .version("v1"));
    }

}
