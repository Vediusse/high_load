package ru.itmo.highload.catering.kitchen.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import ru.itmo.highload.common.dto.PageResponse;
import ru.itmo.highload.common.error.ApiError;
import ru.itmo.highload.common.error.ApiException;
import ru.itmo.highload.catering.order.dto.*;
import ru.itmo.highload.catering.order.entity.OrderStatus;
import ru.itmo.highload.catering.kitchen.client.OrderClient;

@Service
@RequiredArgsConstructor
public class OrderGateway {
    private final OrderClient client;
    private final CircuitBreakerFactory<?, ?> breakers;
    private final ObjectMapper mapper;
    private static final Set<String> BUSINESS_CODES = Set.of("RESOURCE_NOT_FOUND", "ORDER_VERSION_CONFLICT",
            "ORDER_STATUS_CONFLICT", "COMMAND_ID_CONFLICT", "VALIDATION_FAILED", "MALFORMED_JSON");

    public OrderResponse get(UUID id) {
        return call(() -> validate(client.get(id, trace()), id));
    }

    public OrderResponse command(UUID id, KitchenCommand command) {
        return call(() -> {
            OrderResponse result = validate(client.command(id, command, trace()), id);
            OrderStatus target = switch (command.action()) {
                case START_COOKING -> OrderStatus.IN_COOKING;
                case MARK_READY -> OrderStatus.READY;
                case COMPLETE -> OrderStatus.COMPLETED;
            };
            if (result.status() != target || result.version() != command.expectedVersion() + 1) {
                throw new IllegalStateException("Invalid command result");
            }
            return result;
        });
    }

    public PageResponse<OrderResponse> queue(int page, int size) {
        return call(() -> {
            var result = client.queue(page, size, trace());
            if (result == null || result.items() == null || result.page() != page || result.size() != size
                    || result.items().size() > size || (result.hasNext() && result.items().size() != size)) {
                throw new IllegalStateException("Invalid queue response");
            }
            Set<UUID> ids = new java.util.HashSet<>();
            for (var order : result.items()) {
                validate(order, order == null ? null : order.id());
                if (!Set.of(OrderStatus.CONFIRMED, OrderStatus.IN_COOKING, OrderStatus.READY).contains(order.status())
                        || !ids.add(order.id())) throw new IllegalStateException("Invalid queue entry");
            }
            return result;
        });
    }

    private OrderResponse validate(OrderResponse result, UUID id) {
        if (result == null || id == null || !id.equals(result.id()) || result.status() == null
                || result.version() < 0 || result.organizationId() == null || result.deliveryPointId() == null
                || result.requestedDeliveryAt() == null || result.createdAt() == null
                || result.lines() == null || result.totalAmount() == null) {
            throw new IllegalStateException("Invalid order response");
        }
        return result;
    }

    private <T> T call(Supplier<T> request) {
        return breakers.create("order").run(() -> {
            try {
                return request.get();
            } catch (FeignException error) {
                if (Set.of(400, 404, 409, 422).contains(error.status())) {
                    try {
                        ApiError body = mapper.readValue(error.contentUTF8(), ApiError.class);
                        if (BUSINESS_CODES.contains(body.code()) && body.message() != null && body.fieldErrors() != null) {
                            throw new ApiException(HttpStatus.valueOf(error.status()), body.code(), body.message(), body.fieldErrors());
                        }
                    } catch (JsonProcessingException invalidBody) {
                        throw error;
                    }
                }
                throw error;
            }
        }, error -> {
            if (error instanceof ApiException api) throw api;
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", "Сервис заказов временно недоступен");
        });
    }

    private String trace() {
        String trace = MDC.get("traceId");
        return trace == null ? UUID.randomUUID().toString() : trace;
    }
}
