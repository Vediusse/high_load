package ru.itmo.highload.catering.kitchen.application;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import ru.itmo.highload.catering.kitchen.application.dto.in.KitchenCommand;
import ru.itmo.highload.catering.kitchen.application.dto.out.OrderResponse;
import ru.itmo.highload.catering.kitchen.application.dto.out.OrderState;
import ru.itmo.highload.catering.kitchen.application.ports.OrderOperations;
import ru.itmo.highload.catering.kitchen.domain.model.KitchenAction;
import ru.itmo.highload.catering.kitchen.domain.model.KitchenCommandIdentity;
import ru.itmo.highload.common.dto.out.PageResponse;

@RequiredArgsConstructor
public class KitchenService {

    private final OrderOperations orders;
    private final TaskProjection tasks;

    public OrderResponse execute(
            UUID id, long expectedVersion, KitchenAction action, String bearer) {
        // Read the owner even on retry, but let its receipt resolve an already executed command.
        var current = orders.get(id, bearer);
        UUID commandId = KitchenCommandIdentity.forAction(id, action, expectedVersion);
        var result =
                orders.command(
                        id,
                        new KitchenCommand(
                                commandId, action.expectedStatus(), expectedVersion, action),
                        bearer);
        tasks.recordCommand(
                current.version() > result.version() ? current : result, result, action);
        return result;
    }

    public PageResponse<OrderResponse> queue(int page, int size, String bearer) {
        var result = orders.queue(page, size, bearer);
        // Reconcile one bounded batch, oldest observations first. The returned queue is always read
        // from its owner.
        var ids = new HashSet<>(tasks.oldestActiveIds());
        var observed = new ArrayList<>(orders.states(ids, bearer));
        result.items()
                .forEach(order ->
                        observed.add(new OrderState(order.id(), order.status(), order.version())));
        tasks.observeStates(observed);
        return result;
    }
}
