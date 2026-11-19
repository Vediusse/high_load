package ru.itmo.highload.catering.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI corporateCateringOpenApi() {
        return new OpenAPI()
                .servers(List.of(new Server().url("/")))
                .info(
                        new Info()
                                .title("Corporate Catering API")
                                .description("Блюда, категории и постоянное меню")
                                .version("v1"));
    }
}
