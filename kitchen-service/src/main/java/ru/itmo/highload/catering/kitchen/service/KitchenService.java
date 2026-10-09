package ru.itmo.highload.catering.kitchen.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.itmo.highload.catering.kitchen.client.dto.in.KitchenAction;
import ru.itmo.highload.catering.kitchen.client.dto.in.KitchenCommand;
import ru.itmo.highload.catering.kitchen.client.dto.out.OrderResponse;
import ru.itmo.highload.catering.kitchen.client.dto.out.OrderState;
import ru.itmo.highload.common.dto.out.PageResponse;

@Service
@RequiredArgsConstructor
public class KitchenService {
    // Persisted idempotency namespace: changing this would invalidate retries of earlier commands.
    private static final String COMMAND_ID_NAMESPACE = "production:v1:";

    private final OrderGateway orders;
    private final TaskProjection tasks;

    public OrderResponse execute(UUID id, long expectedVersion, KitchenAction action, String bearer) {
        // Read the owner even on retry, but let its receipt resolve an already executed command.
        var current = orders.get(id, bearer);
        UUID commandId = UUID.nameUUIDFromBytes((COMMAND_ID_NAMESPACE + id + ":" + action + ":" + expectedVersion)
                .getBytes(StandardCharsets.UTF_8));
        var result = orders.command(id, new KitchenCommand(commandId, action.expectedStatus(), expectedVersion, action), bearer);
        tasks.observe(current.version() > result.version() ? current : result);
        return result;
    }

    public PageResponse<OrderResponse> queue(int page, int size, String bearer) {
        var result = orders.queue(page, size, bearer);
        // Reconcile one bounded batch, oldest observations first. The returned queue is always read from its owner.
        var ids = new HashSet<>(tasks.oldestActiveIds());
        var observed = new ArrayList<>(orders.states(ids, bearer));
        result.items().forEach(order -> observed.add(new OrderState(order.id(), order.status(), order.version())));
        tasks.observeStates(observed);
        return result;
    }
}
