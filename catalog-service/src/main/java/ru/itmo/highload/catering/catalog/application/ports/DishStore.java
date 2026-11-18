package ru.itmo.highload.catering.catalog.application.ports;

import java.util.Collection;
import java.util.UUID;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.itmo.highload.catering.catalog.domain.model.Dish;
import ru.itmo.highload.catering.catalog.domain.model.DishCategory;
public interface DishStore {
    Mono<Dish> save(Dish dish);
    Mono<Dish> findById(UUID id);
    Flux<Dish> findAllById(Collection<UUID> ids);
    Flux<Dish> findActivePage(UUID afterId, int limit, UUID categoryId);
    Flux<DishCategory> findCategoryLinks(Collection<UUID> ids);
    Mono<Void> deleteCategoryLinks(UUID dishId);
    Mono<Void> addCategoryLink(UUID dishId, UUID categoryId);
}
