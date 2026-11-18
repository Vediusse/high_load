package ru.itmo.highload.catering.kitchen.application.ports;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import ru.itmo.highload.catering.kitchen.application.dto.in.KitchenCommand;
import ru.itmo.highload.catering.kitchen.application.dto.out.OrderResponse;
import ru.itmo.highload.catering.kitchen.application.dto.out.OrderState;
import ru.itmo.highload.common.dto.out.PageResponse;

public interface OrderOperations {
    OrderResponse get(UUID id, String bearer);

    List<OrderState> states(Set<UUID> ids, String bearer);

    OrderResponse command(UUID id, KitchenCommand command, String bearer);

    PageResponse<OrderResponse> queue(int page, int size, String bearer);
}
