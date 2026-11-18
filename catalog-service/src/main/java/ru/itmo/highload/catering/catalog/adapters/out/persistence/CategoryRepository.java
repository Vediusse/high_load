package ru.itmo.highload.catering.catalog.adapters.out.persistence;

import java.util.UUID;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface CategoryRepository extends ReactiveCrudRepository<CategoryRow, UUID> {

    // UUID is already assigned, so creation is explicit; save() updates existing categories.
    @Query("INSERT INTO category (id, name) VALUES (:#{#category.id}, :#{#category.name}) RETURNING *")
    Mono<CategoryRow> insert(@Param("category") CategoryRow category);

    @Query("SELECT * FROM category ORDER BY id LIMIT :limit OFFSET :offset")
    Flux<CategoryRow> findPage(@Param("offset") long offset, @Param("limit") int limit);
}
