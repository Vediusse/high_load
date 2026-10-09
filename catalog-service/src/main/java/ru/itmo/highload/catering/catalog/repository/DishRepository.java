package ru.itmo.highload.catering.catalog.repository;

import java.util.Collection;
import java.util.UUID;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.itmo.highload.catering.catalog.entity.Dish;
import ru.itmo.highload.catering.catalog.entity.DishCategory;

public interface DishRepository extends ReactiveCrudRepository<Dish, UUID> {

    @Query("""
            SELECT d.* FROM dish d
            WHERE d.active = true
              AND (CAST(:afterId AS uuid) IS NULL OR d.id > :afterId)
              AND (CAST(:categoryId AS uuid) IS NULL OR EXISTS (
                  SELECT 1 FROM dish_category dc
                  WHERE dc.dish_id = d.id AND dc.category_id = :categoryId
              ))
            ORDER BY d.id LIMIT :limit
            """)
    Flux<Dish> findActivePage(@Param("afterId") UUID afterId, @Param("limit") int limit,
                              @Param("categoryId") UUID categoryId);

    @Query("SELECT dish_id, category_id FROM dish_category WHERE dish_id IN (:ids)")
    Flux<DishCategory> findCategoryLinks(@Param("ids") Collection<UUID> ids);

    @Modifying
    @Query("DELETE FROM dish_category WHERE dish_id = :dishId")
    Mono<Void> deleteCategoryLinks(@Param("dishId") UUID dishId);

    @Modifying
    @Query("INSERT INTO dish_category (dish_id, category_id) VALUES (:dishId, :categoryId)")
    Mono<Void> addCategoryLink(@Param("dishId") UUID dishId, @Param("categoryId") UUID categoryId);
}
