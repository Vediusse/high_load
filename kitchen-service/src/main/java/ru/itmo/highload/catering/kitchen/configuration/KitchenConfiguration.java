package ru.itmo.highload.catering.kitchen.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import ru.itmo.highload.catering.kitchen.application.*;
import ru.itmo.highload.catering.kitchen.application.ports.*;
import ru.itmo.highload.common.config.UseCaseTransactions;
@Configuration
public class KitchenConfiguration {
    @Bean TaskProjection taskProjection(KitchenTaskStore tasks,PlatformTransactionManager tx) {
        return UseCaseTransactions.proxy(new TaskProjection(tasks),tx,"oldestActiveIds");
    }
    @Bean KitchenService kitchenService(OrderOperations orders,TaskProjection tasks) { return new KitchenService(orders,tasks); }
}
