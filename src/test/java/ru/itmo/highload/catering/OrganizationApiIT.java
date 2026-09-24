package ru.itmo.highload.catering;

import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
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
class OrganizationApiIT extends AbstractPostgresIT {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void organizationAndDeliveryPointCrudUsesDtosAndPagination() throws Exception {
        JsonNode organization = createOrganization("Альфа", "+79991234567");
        UUID organizationId = UUID.fromString(organization.get("id").asText());

        mockMvc.perform(get("/api/v1/organizations/{id}", organizationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Альфа"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.deliveryPoints").doesNotExist());

        mockMvc.perform(put("/api/v1/organizations/{id}", organizationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Альфа Плюс", "phone", "+79990000000"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Альфа Плюс"))
                .andExpect(jsonPath("$.version").value(1));

        createOrganization("Бета", "+79991234568");
        mockMvc.perform(get("/api/v1/organizations").param("page", "0").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "2"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.content").doesNotExist());

        MvcResult pointResult = mockMvc.perform(post(
                        "/api/v1/organizations/{id}/delivery-points",
                        organizationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(pointPayload("Главный офис"))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("/delivery-points/")))
                .andExpect(jsonPath("$.organizationId").value(organizationId.toString()))
                .andExpect(jsonPath("$.active").value(true))
                .andReturn();
        UUID pointId = UUID.fromString(body(pointResult).get("id").asText());

        mockMvc.perform(get("/api/v1/organizations/{id}/delivery-points", organizationId)
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(pointId.toString()))
                .andExpect(jsonPath("$.hasNext").value(false));

        mockMvc.perform(put("/api/v1/delivery-points/{id}", pointId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "name", "Новый офис",
                                "address", "Невский проспект, 2",
                                "contactName", "Мария",
                                "contactPhone", "+79991111111"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Новый офис"));

        mockMvc.perform(delete("/api/v1/delivery-points/{id}", pointId))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/organizations/{id}/delivery-points", organizationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].active").value(false));

        mockMvc.perform(delete("/api/v1/organizations/{id}", organizationId))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/organizations/{id}", organizationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void returnsStableValidationAndNotFoundErrorsWithTraceId() throws Exception {
        mockMvc.perform(post("/api/v1/organizations")
                        .header("X-Trace-Id", "organization-validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", " ", "phone", "8999"))))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("X-Trace-Id", "organization-validation"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.traceId").value("organization-validation"))
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItems("name", "phone")));

        mockMvc.perform(get("/api/v1/organizations").param("size", "51"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("size"));

        mockMvc.perform(get("/api/v1/organizations/{id}/delivery-points", UUID.randomUUID())
                        .param("size", "51"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("size"));

        mockMvc.perform(get("/api/v1/organizations/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void inactiveOrganizationAndDuplicatePointNameAreRejected() throws Exception {
        UUID organizationId = UUID.fromString(createOrganization("Альфа", "+79991234567").get("id").asText());

        mockMvc.perform(post("/api/v1/organizations/{id}/delivery-points", organizationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(pointPayload("Офис"))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/organizations/{id}/delivery-points", organizationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(pointPayload("Офис"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DELIVERY_POINT_NAME_CONFLICT"));

        mockMvc.perform(delete("/api/v1/organizations/{id}", organizationId))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/organizations/{id}/delivery-points", organizationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(pointPayload("Запасной офис"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ORGANIZATION_INACTIVE"));
    }

    private JsonNode createOrganization(String name, String phone) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/organizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", name, "phone", phone))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("/organizations/")))
                .andExpect(jsonPath("$.active").value(true))
                .andReturn();
        return body(result);
    }

    private Map<String, String> pointPayload(String name) {
        return Map.of(
                "name", name,
                "address", "Невский проспект, 1",
                "contactName", "Анна",
                "contactPhone", "+79997654321");
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
