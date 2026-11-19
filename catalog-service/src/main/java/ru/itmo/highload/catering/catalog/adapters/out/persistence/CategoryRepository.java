package ru.itmo.highload.catering.catalog.adapters.out.persistence;

import java.util.UUID;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface CategoryRepository extends ReactiveCrudRepository<CategoryRow, UUID> {

    @Query("SELECT * FROM category ORDER BY id LIMIT :limit OFFSET :offset")
    Flux<CategoryRow> findPage(@Param("offset") long offset, @Param("limit") int limit);
}
