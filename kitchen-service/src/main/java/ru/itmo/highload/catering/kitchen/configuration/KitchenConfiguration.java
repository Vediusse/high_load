package ru.itmo.highload.catering.kitchen.configuration;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import ru.itmo.highload.catering.kitchen.application.KitchenService;
import ru.itmo.highload.catering.kitchen.application.TaskProjection;
import ru.itmo.highload.catering.kitchen.application.dto.out.OrderLineResponse;
import ru.itmo.highload.catering.kitchen.application.ports.KitchenTaskStore;
import ru.itmo.highload.catering.kitchen.application.ports.OrderOperations;
import ru.itmo.highload.common.config.UseCaseTransactions;
import ru.itmo.highload.common.domain.events.EventInbox;
import ru.itmo.highload.common.domain.events.EventOutbox;
import ru.itmo.highload.common.events.JdbcInbox;
import ru.itmo.highload.common.events.JdbcOutbox;

@Configuration
public class KitchenConfiguration {
    @Bean
    Jackson2ObjectMapperBuilderCustomizer orderLineSerialization() {
        return builder ->
                builder.postConfigurer(mapper ->
                        mapper.configOverride(OrderLineResponse.class)
                                .setInclude(
                                        JsonInclude.Value.construct(
                                                JsonInclude.Include.ALWAYS,
                                                JsonInclude.Include.ALWAYS)));
    }

    @Bean
    JdbcOutbox eventOutbox(JdbcTemplate jdbc, ObjectMapper mapper) {
        return new JdbcOutbox(jdbc, mapper);
    }

    @Bean
    EventInbox eventInbox(JdbcTemplate jdbc) {
        return new JdbcInbox(jdbc);
    }

    @Bean
    TaskProjection taskProjection(
            KitchenTaskStore tasks,
            EventOutbox outbox,
            EventInbox inbox,
            PlatformTransactionManager tx) {
        return UseCaseTransactions.proxy(
                new TaskProjection(tasks, outbox, inbox), tx, "oldestActiveIds");
    }

    @Bean
    KitchenService kitchenService(OrderOperations orders, TaskProjection tasks) {
        return new KitchenService(orders, tasks);
    }
}
