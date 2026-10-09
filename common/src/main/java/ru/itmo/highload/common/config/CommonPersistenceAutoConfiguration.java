package ru.itmo.highload.common.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.Bean;
import ru.itmo.highload.common.error.PersistenceExceptionHandler;

@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnClass(name = "org.springframework.dao.DataAccessException")
public class CommonPersistenceAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    PersistenceExceptionHandler persistenceExceptionHandler() { return new PersistenceExceptionHandler(); }
}
