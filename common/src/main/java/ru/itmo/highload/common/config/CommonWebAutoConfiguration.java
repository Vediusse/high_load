package ru.itmo.highload.common.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.Bean;
import ru.itmo.highload.common.error.GlobalExceptionHandler;
import ru.itmo.highload.common.error.TraceFilter;

@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class CommonWebAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    TraceFilter traceFilter() { return new TraceFilter(); }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "catering.web.errors", name = "enabled", havingValue = "true", matchIfMissing = true)
    GlobalExceptionHandler globalExceptionHandler() { return new GlobalExceptionHandler(); }
}
