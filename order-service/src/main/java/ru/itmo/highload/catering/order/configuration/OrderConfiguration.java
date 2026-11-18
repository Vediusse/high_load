package ru.itmo.highload.catering.order.configuration;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import ru.itmo.highload.common.config.UseCaseTransactions;
import ru.itmo.highload.catering.order.application.*;
import ru.itmo.highload.catering.order.application.ports.*;
import ru.itmo.highload.catering.organization.application.OrganizationService;
import ru.itmo.highload.catering.organization.application.ports.*;
import ru.itmo.highload.catering.catalog.application.ports.DishCatalog;
@Configuration
public class OrderConfiguration {
    @Bean ru.itmo.highload.common.events.JdbcOutbox eventOutbox(org.springframework.jdbc.core.JdbcTemplate jdbc,com.fasterxml.jackson.databind.ObjectMapper mapper) {
        return new ru.itmo.highload.common.events.JdbcOutbox(jdbc,mapper);
    }
    @Bean OrganizationService organizationService(OrganizationStore organizations,DeliveryPointStore points,PlatformTransactionManager tx) {
        return UseCaseTransactions.proxy(new OrganizationService(organizations,points),tx,"get*","list*","requireActive*");
    }
    @Bean OrderService orderService(OrderStore orders,HistoryStore history,OrganizationService organizations,DishCatalog catalog,Clock clock,ru.itmo.highload.common.domain.events.EventOutbox outbox,PlatformTransactionManager tx) {
        return UseCaseTransactions.proxy(new OrderService(orders,history,organizations,catalog,clock,outbox),tx,"get*","list*","states","kitchenQueue");
    }
    @Bean ru.itmo.highload.common.domain.events.EventInbox eventInbox(org.springframework.jdbc.core.JdbcTemplate jdbc) { return new ru.itmo.highload.common.events.JdbcInbox(jdbc); }
    @Bean FileReferences fileReferences(OrderService orders,FileReferenceStore links,ru.itmo.highload.common.domain.events.EventInbox inbox,PlatformTransactionManager tx) {
        return UseCaseTransactions.proxy(new FileReferences(orders,links,inbox),tx,"list");
    }
    @Bean KitchenCommands kitchenCommands(CommandReceipts receipts,OrderService orders,PlatformTransactionManager tx) {
        return UseCaseTransactions.proxy(new KitchenCommands(receipts,orders),tx);
    }
}
