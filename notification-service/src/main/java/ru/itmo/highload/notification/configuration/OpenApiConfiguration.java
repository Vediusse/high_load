package ru.itmo.highload.notification.configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {
    @Bean
    OpenAPI openApi() {
        return new OpenAPI()
                .servers(List.of(new Server().url("/")))
                .info(new Info().title("Уведомления").version("v1"));
    }
}
