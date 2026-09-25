package ru.itmo.highload.catering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import ru.itmo.highload.catering.catalog.entity.Category;
import ru.itmo.highload.catering.catalog.entity.Dish;
import ru.itmo.highload.catering.catalog.repository.CategoryRepository;
import ru.itmo.highload.catering.catalog.repository.DishRepository;
import ru.itmo.highload.catering.organization.entity.Organization;
import ru.itmo.highload.catering.organization.repository.OrganizationRepository;

@SpringBootTest
class PersistenceConstraintsIT extends AbstractPostgresIT {

    @Autowired
    OrganizationRepository organizationRepository;

    @Autowired
    CategoryRepository categoryRepository;

    @Autowired
    DishRepository dishRepository;

    @Test
    void databaseEnforcesForeignKeysUniqueNamesAndPositivePrice() {
        Organization organization = organizationRepository.saveAndFlush(
                new Organization("Альфа", "+79991234567"));
        UUID firstPointId = UUID.randomUUID();
        jdbcTemplate.update("""
                        INSERT INTO delivery_point (
                            id, organization_id, name, address, contact_name, contact_phone, active
                        ) VALUES (?, ?, ?, ?, ?, ?, true)
                        """,
                firstPointId,
                organization.getId(),
                "Офис",
                "Адрес",
                "Анна",
                "+79997654321");

        assertThatThrownBy(() -> jdbcTemplate.update("""
                        INSERT INTO delivery_point (
                            id, organization_id, name, address, contact_name, contact_phone, active
                        ) VALUES (?, ?, ?, ?, ?, ?, true)
                        """,
                UUID.randomUUID(),
                organization.getId(),
                "Офис",
                "Другой адрес",
                "Мария",
                "+79991111111"))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                        INSERT INTO delivery_point (
                            id, organization_id, name, address, contact_name, contact_phone, active
                        ) VALUES (?, ?, ?, ?, ?, ?, true)
                        """,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Чужой офис",
                "Адрес",
                "Иван",
                "+79992222222"))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                        INSERT INTO dish (id, name, description, current_price, active, version)
                        VALUES (?, ?, '', ?, true, 0)
                        """,
                UUID.randomUUID(),
                "Некорректное блюдо",
                new BigDecimal("-1.00")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void repositoriesPersistManyToManyAndApplyActiveCursorQuery() {
        Category soups = categoryRepository.saveAndFlush(new Category("Супы"));
        Category lunches = categoryRepository.saveAndFlush(new Category("Обеды"));
        Dish borsch = dishRepository.saveAndFlush(
                new Dish("Борщ", "", new BigDecimal("180.00"), Set.of(soups, lunches)));
        Dish inactive = dishRepository.saveAndFlush(
                new Dish("Снятое блюдо", "", new BigDecimal("100.00"), Set.of(soups)));
        inactive.deactivate();
        dishRepository.saveAndFlush(inactive);

        List<UUID> activeIds = dishRepository.findActiveIdsAfter(null, soups.getId(), PageRequest.of(0, 51));
        List<Dish> loaded = dishRepository.findAllWithCategoriesByIdIn(activeIds);

        assertThat(activeIds).containsExactly(borsch.getId());
        assertThat(loaded).singleElement().satisfies(dish -> {
            assertThat(dish.getCategories()).extracting(Category::getName)
                    .containsExactlyInAnyOrder("Супы", "Обеды");
            assertThat(dish.isActive()).isTrue();
        });
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM dish_category WHERE dish_id = ?",
                Long.class,
                borsch.getId())).isEqualTo(2L);
    }
}
