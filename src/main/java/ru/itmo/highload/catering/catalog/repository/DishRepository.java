package ru.itmo.highload.catering.catalog.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.itmo.highload.catering.catalog.entity.Dish;

public interface DishRepository extends JpaRepository<Dish, UUID> {

    @Query("""
            select d.id
            from Dish d
            where d.active = true
              and (:afterId is null or d.id > :afterId)
              and (:categoryId is null or exists (
                    select category.id from d.categories category where category.id = :categoryId
              ))
            order by d.id asc
            """)
    List<UUID> findActiveIdsAfter(
            @Param("afterId") UUID afterId,
            @Param("categoryId") UUID categoryId,
            Pageable pageable);

    @EntityGraph(attributePaths = "categories")
    List<Dish> findAllWithCategoriesByIdIn(Collection<UUID> ids);

    boolean existsByCategoriesId(UUID categoryId);
}
