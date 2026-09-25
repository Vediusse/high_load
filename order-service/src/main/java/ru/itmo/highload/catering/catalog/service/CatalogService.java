package ru.itmo.highload.catering.catalog.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import java.util.*;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import ru.itmo.highload.catering.catalog.client.CatalogClient;
import ru.itmo.highload.catering.catalog.dto.ActiveDishData;
import ru.itmo.highload.catering.common.error.ApiError;
import ru.itmo.highload.catering.common.error.ApiException;

@Service
public class CatalogService {
    private final CatalogClient client;
    private final CircuitBreakerFactory<?, ?> breakers;
    private final ObjectMapper mapper;

    public CatalogService(CatalogClient client, CircuitBreakerFactory<?, ?> breakers, ObjectMapper mapper) {
        this.client = client;
        this.breakers = breakers;
        this.mapper = mapper;
    }

    public Map<UUID, ActiveDishData> getActiveDishPrices(Set<UUID> ids) {
        Objects.requireNonNull(ids, "Набор блюд обязателен");
        if (ids.isEmpty()) return Map.of();
        String traceId = org.slf4j.MDC.get("traceId");
        String propagatedTrace = traceId == null ? UUID.randomUUID().toString() : traceId;
        return breakers.create("catalog").run(() -> fetch(ids, propagatedTrace), error -> {
            if (error instanceof ApiException api) throw api;
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", "Каталог временно недоступен");
        });
    }

    private Map<UUID, ActiveDishData> fetch(Set<UUID> ids, String traceId) {
        List<CatalogClient.DishSnapshot> snapshots;
        try {
            snapshots = client.snapshots(new CatalogClient.SnapshotRequest(ids), traceId);
        } catch (FeignException error) {
            if (error.status() == 404 || error.status() == 422) {
                try {
                    ApiError body = mapper.readValue(error.contentUTF8(), ApiError.class);
                    if ((error.status() == 404 && "RESOURCE_NOT_FOUND".equals(body.code()))
                            || (error.status() == 422 && "DISH_INACTIVE".equals(body.code()))) {
                        throw new ApiException(HttpStatus.valueOf(error.status()), body.code(), body.message());
                    }
                } catch (com.fasterxml.jackson.core.JsonProcessingException invalidBody) {
                    throw error;
                }
            }
            throw error;
        }
        Map<UUID, ActiveDishData> result = new LinkedHashMap<>();
        if (snapshots == null) throw new IllegalStateException("Empty catalog response");
        for (var item : snapshots) {
            if (item == null || !ids.contains(item.id()) || !item.active() || item.name() == null
                    || item.name().isBlank() || item.price() == null || item.price().signum() <= 0
                    || item.price().scale() > 2 || result.containsKey(item.id())) {
                throw new IllegalStateException("Invalid catalog snapshot");
            }
            result.put(item.id(), new ActiveDishData(item.id(), item.name(), item.price()));
        }
        if (!result.keySet().equals(ids)) throw new IllegalStateException("Incomplete catalog snapshot");
        return result;
    }
}
