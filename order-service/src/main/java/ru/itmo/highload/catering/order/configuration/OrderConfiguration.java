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
    @Bean OrganizationService organizationService(OrganizationStore organizations,DeliveryPointStore points,PlatformTransactionManager tx) {
        return UseCaseTransactions.proxy(new OrganizationService(organizations,points),tx,"get*","list*","requireActive*");
    }
    @Bean OrderService orderService(OrderStore orders,HistoryStore history,OrganizationService organizations,DishCatalog catalog,Clock clock,PlatformTransactionManager tx) {
        return UseCaseTransactions.proxy(new OrderService(orders,history,organizations,catalog,clock),tx,"get*","list*","states","kitchenQueue");
    }
    @Bean KitchenCommands kitchenCommands(CommandReceipts receipts,OrderService orders,PlatformTransactionManager tx) {
        return UseCaseTransactions.proxy(new KitchenCommands(receipts,orders),tx);
    }
}
