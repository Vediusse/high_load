package ru.itmo.highload.catering.catalog.repository;

import java.util.UUID;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.itmo.highload.catering.catalog.entity.Category;

public interface CategoryRepository extends ReactiveCrudRepository<Category, UUID> {

    // UUID is already assigned, so creation is explicit; save() updates existing categories.
    @Query("INSERT INTO category (id, name) VALUES (:#{#category.id}, :#{#category.name}) RETURNING *")
    Mono<Category> insert(@Param("category") Category category);

    @Query("SELECT * FROM category ORDER BY id LIMIT :limit OFFSET :offset")
    Flux<Category> findPage(@Param("offset") long offset, @Param("limit") int limit);
}
