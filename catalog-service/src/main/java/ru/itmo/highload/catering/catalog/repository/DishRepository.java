package ru.itmo.highload.catering.catalog.repository;

import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.dialect.RenderContextFactory;
import org.springframework.data.relational.core.sql.Condition;
import org.springframework.data.relational.core.sql.SQL;
import org.springframework.data.relational.core.sql.Select;
import org.springframework.data.relational.core.sql.Table;
import org.springframework.data.relational.core.sql.render.SqlRenderer;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.itmo.highload.catering.catalog.entity.Dish;
import static org.springframework.data.relational.core.query.Criteria.where;
import static org.springframework.data.relational.core.query.Query.query;

@Repository
public class DishRepository {
    private final R2dbcEntityTemplate template;
    private final SqlRenderer renderer;

    public DishRepository(R2dbcEntityTemplate template) {
        this.template = template;
        this.renderer = SqlRenderer.create(new RenderContextFactory(
                template.getDataAccessStrategy().getDialect()).createRenderContext());
    }

    public Mono<Dish> insert(Dish dish) {
        return template.insert(dish);
    }

    public Mono<Dish> update(Dish dish) {
        return template.update(dish);
    }

    public Mono<Dish> findById(UUID id) {
        return template.selectOne(query(where("id").is(id)), Dish.class);
    }

    public Flux<Dish> findAllById(Set<UUID> ids) {
        return template.select(Dish.class).matching(query(where("id").in(ids))).all();
    }

    public Flux<Dish> findActivePage(UUID afterId, int fetchSize, UUID categoryId) {
        if (categoryId != null) return findActivePageByCategory(afterId, fetchSize, categoryId);
        var criteria = where("active").is(true);
        if (afterId != null) criteria = criteria.and("id").greaterThan(afterId);
        return template.select(Dish.class).matching(query(criteria).sort(Sort.by("id")).limit(fetchSize)).all();
    }

    private Flux<Dish> findActivePageByCategory(UUID afterId, int fetchSize, UUID categoryId) {
        Table dish = Table.create("dish").as("d");
        Table link = Table.create("dish_category").as("dc");
        Condition filter = dish.column("active").isEqualTo(SQL.literalOf(true))
                .and(link.column("category_id").isEqualTo(SQL.bindMarker(":categoryId")));
        if (afterId != null) filter = filter.and(dish.column("id").isGreater(SQL.bindMarker(":afterId")));
        // The unique (dish_id, category_id) key keeps one row per dish before pagination.
        Select select = Select.builder().select(dish.asterisk()).from(dish).limit(fetchSize)
                .join(link).on(dish.column("id").isEqualTo(link.column("dish_id")))
                .where(filter).orderBy(dish.column("id")).build();
        var statement = template.getDatabaseClient().sql(renderer.render(select)).bind("categoryId", categoryId);
        if (afterId != null) statement = statement.bind("afterId", afterId);
        return statement.map((row, metadata) -> template.getConverter().read(Dish.class, row, metadata)).all();
    }
}
