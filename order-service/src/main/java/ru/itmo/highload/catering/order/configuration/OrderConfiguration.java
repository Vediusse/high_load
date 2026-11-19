package ru.itmo.highload.catering.order.configuration;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import ru.itmo.highload.catering.catalog.application.ports.DishCatalog;
import ru.itmo.highload.catering.order.application.FileReferences;
import ru.itmo.highload.catering.order.application.KitchenCommands;
import ru.itmo.highload.catering.order.application.OrderService;
import ru.itmo.highload.catering.order.application.dto.out.OrderLineResponse;
import ru.itmo.highload.catering.order.application.ports.CommandReceipts;
import ru.itmo.highload.catering.order.application.ports.FileReferenceStore;
import ru.itmo.highload.catering.order.application.ports.HistoryStore;
import ru.itmo.highload.catering.order.application.ports.OrderStore;
import ru.itmo.highload.catering.organization.application.OrganizationService;
import ru.itmo.highload.catering.organization.application.ports.DeliveryPointStore;
import ru.itmo.highload.catering.organization.application.ports.OrganizationStore;
import ru.itmo.highload.common.config.UseCaseTransactions;
import ru.itmo.highload.common.domain.events.EventInbox;
import ru.itmo.highload.common.domain.events.EventOutbox;
import ru.itmo.highload.common.events.JdbcInbox;
import ru.itmo.highload.common.events.JdbcOutbox;

@Configuration
public class OrderConfiguration {
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
    OrganizationService organizationService(
            OrganizationStore organizations,
            DeliveryPointStore points,
            PlatformTransactionManager tx) {
        return UseCaseTransactions.proxy(
                new OrganizationService(organizations, points),
                tx,
                "get*",
                "list*",
                "requireActive*");
    }

    @Bean
    OrderService orderService(
            OrderStore orders,
            HistoryStore history,
            OrganizationService organizations,
            DishCatalog catalog,
            Clock clock,
            EventOutbox outbox,
            PlatformTransactionManager tx) {
        return UseCaseTransactions.proxy(
                new OrderService(orders, history, organizations, catalog, clock, outbox),
                tx,
                "get*",
                "list*",
                "states",
                "kitchenQueue");
    }

    @Bean
    EventInbox eventInbox(JdbcTemplate jdbc) {
        return new JdbcInbox(jdbc);
    }

    @Bean
    FileReferences fileReferences(
            OrderService orders,
            FileReferenceStore links,
            EventInbox inbox,
            PlatformTransactionManager tx) {
        return UseCaseTransactions.proxy(new FileReferences(orders, links, inbox), tx, "list");
    }

    @Bean
    KitchenCommands kitchenCommands(
            CommandReceipts receipts, OrderService orders, PlatformTransactionManager tx) {
        return UseCaseTransactions.proxy(new KitchenCommands(receipts, orders), tx);
    }
}
