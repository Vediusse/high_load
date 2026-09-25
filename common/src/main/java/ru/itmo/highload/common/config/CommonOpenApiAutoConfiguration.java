package ru.itmo.highload.common.config;

import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnClass(OpenApiCustomizer.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class CommonOpenApiAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(name = "traceIdHeaderCustomizer")
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
