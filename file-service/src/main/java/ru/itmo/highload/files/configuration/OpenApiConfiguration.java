package ru.itmo.highload.files.configuration;
import org.springframework.context.annotation.*;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
@Configuration
public class OpenApiConfiguration {
    @Bean OpenAPI openApi() { return new OpenAPI().servers(java.util.List.of(new Server().url("/")))
            .info(new Info().title("Файлы заказов").version("v1")); }
}
