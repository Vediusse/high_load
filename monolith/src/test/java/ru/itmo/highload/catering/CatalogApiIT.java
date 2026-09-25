package ru.itmo.highload.catering;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class CatalogApiIT extends AbstractPostgresIT {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void catalogCrudManyToManyAndCursorPaginationWorkEndToEnd() throws Exception {
        UUID soups = createCategory("Супы");
        UUID mains = createCategory("Основные блюда");
        UUID unused = createCategory("Десерты");

        UUID borsch = createDish("Борщ", "180.00", Set.of(soups));
        UUID cutlet = createDish("Куриная котлета", "320.00", Set.of(mains));
        UUID lunch = createDish("Комплексный обед", "450.00", Set.of(soups, mains));
        long initialCutletVersion = body(mockMvc.perform(get("/api/v1/dishes/{id}", cutlet))
                        .andExpect(status().isOk())
                        .andReturn())
                .get("version")
                .asLong();

        MvcResult firstPageResult = mockMvc.perform(get("/api/v1/dishes").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("X-Total-Count"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.nextCursor").isNotEmpty())
                .andExpect(jsonPath("$.total").doesNotExist())
                .andReturn();
        String cursor = body(firstPageResult).get("nextCursor").asText();

        mockMvc.perform(get("/api/v1/dishes").param("afterId", cursor).param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.nextCursor").isEmpty());

        mockMvc.perform(get("/api/v1/dishes")
                        .param("categoryId", soups.toString())
                        .param("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[*].id", containsInAnyOrder(borsch.toString(), lunch.toString())));

        mockMvc.perform(put("/api/v1/dishes/{id}", cutlet)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dishPayload("Котлета с пюре", "350.00", Set.of(soups, mains)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Котлета с пюре"))
                .andExpect(jsonPath("$.currentPrice").value(350.00))
                .andExpect(jsonPath("$.categoryIds.length()").value(2))
                .andExpect(jsonPath("$.version").value(
                        org.hamcrest.Matchers.greaterThan((int) initialCutletVersion)));

        mockMvc.perform(get("/api/v1/dishes/{id}", cutlet))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories").doesNotExist())
                .andExpect(jsonPath("$.hibernateLazyInitializer").doesNotExist());

        mockMvc.perform(delete("/api/v1/categories/{id}", soups))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CATEGORY_IN_USE"));

        mockMvc.perform(delete("/api/v1/dishes/{id}", borsch))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/dishes/{id}", borsch))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        mockMvc.perform(get("/api/v1/dishes").param("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[*].id", not(org.hamcrest.Matchers.hasItem(borsch.toString()))));

        mockMvc.perform(put("/api/v1/categories/{id}", unused)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Выпечка"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Выпечка"));
        mockMvc.perform(delete("/api/v1/categories/{id}", unused))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/categories").param("page", "0").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.hasNext").value(true));
    }

    @Test
    void catalogReturnsStableValidationConflictAndNotFoundErrors() throws Exception {
        UUID categoryId = createCategory("Супы");

        mockMvc.perform(post("/api/v1/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Супы"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATEGORY_NAME_CONFLICT"));

        Map<String, Object> invalidDish = dishPayload("Борщ", "0.00", Set.of(categoryId));
        mockMvc.perform(post("/api/v1/dishes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(invalidDish)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("currentPrice"));

        mockMvc.perform(post("/api/v1/dishes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dishPayload("Щи", "190.00", Set.of(UUID.randomUUID())))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/dishes").param("limit", "51"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("limit"));
        mockMvc.perform(get("/api/v1/categories").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("size"));
        mockMvc.perform(get("/api/v1/categories").param("size", "51"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("size"));
        mockMvc.perform(get("/api/v1/dishes").param("afterId", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("afterId"));
    }

    private UUID createCategory(String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", name))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(body(result).get("id").asText());
    }

    private UUID createDish(String name, String price, Set<UUID> categoryIds) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/dishes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dishPayload(name, price, categoryIds))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(body(result).get("id").asText());
    }

    private Map<String, Object> dishPayload(String name, String price, Set<UUID> categoryIds) {
        return Map.of(
                "name", name,
                "description", "Описание",
                "currentPrice", new BigDecimal(price),
                "categoryIds", categoryIds);
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
