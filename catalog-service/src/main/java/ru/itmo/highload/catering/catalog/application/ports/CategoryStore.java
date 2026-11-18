package ru.itmo.highload.catering.catalog.application.ports;

import java.util.Collection;
import java.util.UUID;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.itmo.highload.catering.catalog.domain.model.Category;
public interface CategoryStore {
    Mono<Category> insert(Category category);
    Mono<Category> save(Category category);
    Mono<Category> findById(UUID id);
    Flux<Category> findAllById(Collection<UUID> ids);
    Flux<Category> findPage(long offset, int limit);
    Mono<Void> delete(Category category);
}
