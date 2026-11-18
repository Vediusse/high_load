package ru.itmo.highload.catering.catalog.adapters.out.persistence;

import java.util.Collection;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.itmo.highload.catering.catalog.application.ports.DishStore;
import ru.itmo.highload.catering.catalog.domain.model.Dish;
import ru.itmo.highload.catering.catalog.domain.model.DishCategory;
@Repository
@RequiredArgsConstructor
public class DishPersistence implements DishStore {
    private final DishRepository repository;
    public Mono<Dish> save(Dish value) { return repository.save(DishRow.from(value)).map(saved -> saved.toDomain(value.getCategories())); }
    public Mono<Dish> findById(UUID id) { return repository.findById(id).map(DishRow::toDomain); }
    public Flux<Dish> findAllById(Collection<UUID> ids) { return repository.findAllById(ids).map(DishRow::toDomain); }
    public Flux<Dish> findActivePage(UUID afterId,int limit,UUID categoryId) { return repository.findActivePage(afterId,limit,categoryId).map(DishRow::toDomain); }
    public Flux<DishCategory> findCategoryLinks(Collection<UUID> ids) { return repository.findCategoryLinks(ids); }
    public Mono<Void> deleteCategoryLinks(UUID id) { return repository.deleteCategoryLinks(id); }
    public Mono<Void> addCategoryLink(UUID dishId,UUID categoryId) { return repository.addCategoryLink(dishId,categoryId); }
}
