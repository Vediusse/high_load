package ru.itmo.highload.catering.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI corporateCateringOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Corporate Catering API")
                        .description("API монолита корпоративного питания: справочники, меню и полный жизненный цикл заказа")
                        .version("v1"));
    }

    @Bean
    OpenApiCustomizer traceIdHeaderCustomizer() {
        return openApi -> openApi.getPaths().forEach((path, pathItem) -> {
            if (!path.startsWith("/api/v1/")) {
                return;
            }
            pathItem.readOperations().forEach(operation -> operation.addParametersItem(new HeaderParameter()
                    .name("X-Trace-Id")
                    .required(false)
                    .description("Необязательный безопасный идентификатор запроса; возвращается в ответе")
                    .schema(new StringSchema()
                            .minLength(1)
                            .maxLength(64)
                            .pattern("[A-Za-z0-9._-]{1,64}"))));
        });
    }
}
