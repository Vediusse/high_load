package ru.itmo.highload.catering.catalog.adapters.out.persistence;

import java.util.Collection;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.itmo.highload.catering.catalog.application.ports.CategoryStore;
import ru.itmo.highload.catering.catalog.domain.DataConflict;
import ru.itmo.highload.catering.catalog.domain.model.Category;

@Repository
@RequiredArgsConstructor
public class CategoryPersistence implements CategoryStore {
    private final CategoryRepository repository;
    private final R2dbcEntityTemplate template;

    public Mono<Category> insert(Category value) {
        return template.insert(CategoryRow.from(value))
                .map(CategoryRow::toDomain)
                .onErrorMap(DataIntegrityViolationException.class, DataConflict::new);
    }

    public Mono<Category> save(Category value) {
        return repository
                .save(CategoryRow.from(value))
                .map(CategoryRow::toDomain)
                .onErrorMap(DataIntegrityViolationException.class, DataConflict::new);
    }

    public Mono<Category> findById(UUID id) {
        return repository.findById(id).map(CategoryRow::toDomain);
    }

    public Flux<Category> findAllById(Collection<UUID> ids) {
        return repository.findAllById(ids).map(CategoryRow::toDomain);
    }

    public Flux<Category> findPage(long offset, int limit) {
        return repository.findPage(offset, limit).map(CategoryRow::toDomain);
    }

    public Mono<Void> delete(Category value) {
        return repository
                .deleteById(value.getId())
                .onErrorMap(DataIntegrityViolationException.class, DataConflict::new);
    }
}
