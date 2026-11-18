package ru.itmo.highload.catering.kitchen.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import ru.itmo.highload.catering.kitchen.application.*;
import ru.itmo.highload.catering.kitchen.application.ports.*;
import ru.itmo.highload.common.config.UseCaseTransactions;
@Configuration
public class KitchenConfiguration {
    @Bean ru.itmo.highload.common.events.JdbcOutbox eventOutbox(org.springframework.jdbc.core.JdbcTemplate jdbc,com.fasterxml.jackson.databind.ObjectMapper mapper) {
        return new ru.itmo.highload.common.events.JdbcOutbox(jdbc,mapper);
    }
    @Bean ru.itmo.highload.common.domain.events.EventInbox eventInbox(org.springframework.jdbc.core.JdbcTemplate jdbc) { return new ru.itmo.highload.common.events.JdbcInbox(jdbc); }
    @Bean TaskProjection taskProjection(KitchenTaskStore tasks,ru.itmo.highload.common.domain.events.EventOutbox outbox,ru.itmo.highload.common.domain.events.EventInbox inbox,PlatformTransactionManager tx) {
        return UseCaseTransactions.proxy(new TaskProjection(tasks,outbox,inbox),tx,"oldestActiveIds");
    }
    @Bean KitchenService kitchenService(OrderOperations orders,TaskProjection tasks) { return new KitchenService(orders,tasks); }
}
