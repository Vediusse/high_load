package ru.itmo.highload.catering.catalog.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.ReactiveTransactionManager;
import ru.itmo.highload.catering.catalog.adapters.out.persistence.SpringReactiveUnitOfWork;
import ru.itmo.highload.catering.catalog.application.CatalogService;
import ru.itmo.highload.catering.catalog.application.ports.CategoryStore;
import ru.itmo.highload.catering.catalog.application.ports.DishStore;
import ru.itmo.highload.catering.catalog.application.ports.ReactiveUnitOfWork;

@Configuration
public class CatalogConfiguration {
    @Bean
    ReactiveUnitOfWork catalogTransactions(ReactiveTransactionManager manager) {
        return new SpringReactiveUnitOfWork(manager);
    }

    @Bean
    CatalogService catalogService(
            CategoryStore categories, DishStore dishes, ReactiveUnitOfWork transaction) {
        return new CatalogService(categories, dishes, transaction);
    }
}
