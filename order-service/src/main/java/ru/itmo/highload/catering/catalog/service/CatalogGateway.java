package ru.itmo.highload.catering.catalog.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import java.util.*;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import ru.itmo.highload.catering.catalog.client.CatalogClient;
import ru.itmo.highload.catering.catalog.client.dto.in.SnapshotRequest;
import ru.itmo.highload.catering.catalog.client.dto.out.DishSnapshot;
import ru.itmo.highload.catering.catalog.dto.out.ActiveDishData;
import ru.itmo.highload.common.dto.out.ApiError;
import ru.itmo.highload.common.error.ApiException;

@Service
public class CatalogGateway {
    private final CatalogClient client;
    private final CircuitBreakerFactory<?, ?> breakers;
    private final ObjectMapper mapper;

    public CatalogGateway(CatalogClient client, CircuitBreakerFactory<?, ?> breakers, ObjectMapper mapper) {
        this.client = client;
        this.breakers = breakers;
        this.mapper = mapper;
    }

    public Map<UUID, ActiveDishData> getActiveDishPrices(Set<UUID> ids, String bearer) {
        Objects.requireNonNull(ids, "Набор блюд обязателен");
        if (ids.isEmpty()) return Map.of();
        String traceId = org.slf4j.MDC.get("traceId");
        String propagatedTrace = traceId == null ? UUID.randomUUID().toString() : traceId;
        var requested = new ArrayList<>(ids);
        Map<UUID, ActiveDishData> result = new LinkedHashMap<>();
        for (int offset = 0; offset < requested.size(); offset += CatalogClient.MAX_SNAPSHOT_IDS) {
            Set<UUID> batch = new LinkedHashSet<>(requested.subList(offset,
                    Math.min(offset + CatalogClient.MAX_SNAPSHOT_IDS, requested.size())));
            var snapshots = breakers.create("catalog").run(() -> fetch(batch, propagatedTrace, bearer), error -> {
                if (error instanceof ApiException api) throw api;
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", "Каталог временно недоступен");
            });
            result.putAll(snapshots);
        }
        return result;
    }

    private Map<UUID, ActiveDishData> fetch(Set<UUID> ids, String traceId, String bearer) {
        List<DishSnapshot> snapshots;
        try {
            snapshots = client.snapshots(new SnapshotRequest(ids), bearer, traceId);
        } catch (FeignException error) {
            // A security status is sufficient; the default HTTP transport can omit a 401 body.
            if (error.status() == 401)
                throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "Требуется действительный токен");
            if (error.status() == 403)
                throw new ApiException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Недостаточно прав");
            if (Set.of(404, 422).contains(error.status())) {
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
