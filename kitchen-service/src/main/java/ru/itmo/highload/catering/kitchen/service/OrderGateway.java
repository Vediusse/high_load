package ru.itmo.highload.catering.kitchen.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import ru.itmo.highload.catering.kitchen.client.OrderClient;
import ru.itmo.highload.catering.kitchen.client.dto.in.KitchenCommand;
import ru.itmo.highload.catering.kitchen.client.dto.in.OrderStatesRequest;
import ru.itmo.highload.catering.kitchen.client.dto.out.OrderResponse;
import ru.itmo.highload.catering.kitchen.client.dto.out.OrderState;
import ru.itmo.highload.catering.kitchen.client.dto.out.OrderStatus;
import ru.itmo.highload.common.dto.out.ApiError;
import ru.itmo.highload.common.dto.out.PageResponse;
import ru.itmo.highload.common.error.ApiException;

@Service
@RequiredArgsConstructor
public class OrderGateway {
    private final OrderClient client;
    private final CircuitBreakerFactory<?, ?> breakers;
    private final ObjectMapper mapper;
    private static final Set<String> BUSINESS_CODES = Set.of("RESOURCE_NOT_FOUND", "ORDER_VERSION_CONFLICT",
            "ORDER_STATUS_CONFLICT", "COMMAND_ID_CONFLICT", "VALIDATION_FAILED", "MALFORMED_JSON");

    public OrderResponse get(UUID id, String bearer) {
        return call(() -> validate(client.get(id, bearer, trace()), id));
    }

    public List<OrderState> states(Set<UUID> ids, String bearer) {
        if (ids.isEmpty()) return List.of();
        return call(() -> {
            var result = client.states(new OrderStatesRequest(ids), bearer, trace());
            if (result == null || result.size() != ids.size()) {
                throw new IllegalStateException("Incomplete order states");
            }
            Set<UUID> received = new HashSet<>();
            for (var state : result) {
                if (state == null || !ids.contains(state.id()) || state.status() == null
                        || state.version() < 0 || !received.add(state.id())) {
                    throw new IllegalStateException("Invalid order state");
                }
            }
            return result;
        });
    }

    public OrderResponse command(UUID id, KitchenCommand command, String bearer) {
        return call(() -> {
            OrderResponse result = validate(client.command(id, command, bearer, trace()), id);
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

    public PageResponse<OrderResponse> queue(int page, int size, String bearer) {
        return call(() -> {
            var result = client.queue(page, size, bearer, trace());
            if (result == null || result.items() == null || result.page() != page || result.size() != size
                    || result.items().size() > size || (result.hasNext() && result.items().size() != size)) {
                throw new IllegalStateException("Invalid queue response");
            }
            Set<UUID> ids = new HashSet<>();
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
                // A security status is sufficient; the default HTTP transport can omit a 401 body.
                if (error.status() == 401)
                    throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "Требуется действительный токен");
                if (error.status() == 403)
                    throw new ApiException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Недостаточно прав");
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
