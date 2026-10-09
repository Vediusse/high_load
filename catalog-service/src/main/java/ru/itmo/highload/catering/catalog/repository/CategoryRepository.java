package ru.itmo.highload.catering.catalog.repository;

import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.itmo.highload.catering.catalog.entity.Category;
import static org.springframework.data.relational.core.query.Criteria.where;
import static org.springframework.data.relational.core.query.Query.query;

@Repository
public class CategoryRepository {
    private final R2dbcEntityTemplate template;

    public CategoryRepository(R2dbcEntityTemplate template) {
        this.template = template;
    }

    public Mono<Category> insert(Category category) {
        return template.insert(category);
    }

    public Mono<Category> update(Category category) {
        return template.update(category);
    }

    public Mono<Void> delete(Category category) {
        return template.delete(category).then();
    }

    public Mono<Category> findById(UUID id) {
        return template.selectOne(query(where("id").is(id)), Category.class);
    }

    public Flux<Category> findAllById(Set<UUID> ids) {
        return template.select(Category.class).matching(query(where("id").in(ids))).all();
    }

    public Flux<Category> findPage(PageRequest page) {
        return template.select(Category.class).matching(query(where("id").isNotNull())
                .sort(Sort.by("id")).offset(page.getOffset()).limit(page.getPageSize() + 1)).all();
    }
}
