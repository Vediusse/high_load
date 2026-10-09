package ru.itmo.highload.catering.catalog.repository;

import java.util.*;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.itmo.highload.catering.catalog.entity.DishCategory;
import static org.springframework.data.relational.core.query.Criteria.where;
import static org.springframework.data.relational.core.query.Query.query;

@Repository
public class DishCategoryRepository {
    private final R2dbcEntityTemplate template;

    public DishCategoryRepository(R2dbcEntityTemplate template) {
        this.template = template;
    }

    public Mono<Void> replace(UUID dishId, Set<UUID> categoryIds) {
        return template.delete(DishCategory.class).matching(query(where("dishId").is(dishId))).all()
                .thenMany(Flux.fromIterable(categoryIds)
                        .concatMap(categoryId -> template.insert(new DishCategory(dishId, categoryId))))
                .then();
    }

    public Mono<Map<UUID, Set<UUID>>> findCategoryIdsByDishIds(List<UUID> ids) {
        if (ids.isEmpty()) return Mono.just(Map.of());
        return template.select(DishCategory.class).matching(query(where("dishId").in(ids))).all()
                .collect(() -> new HashMap<UUID, Set<UUID>>(), (map, link) ->
                        map.computeIfAbsent(link.dishId(), key -> new LinkedHashSet<>()).add(link.categoryId()));
    }
}
