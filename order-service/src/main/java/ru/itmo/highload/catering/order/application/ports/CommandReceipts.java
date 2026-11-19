package ru.itmo.highload.catering.order.application.ports;

import java.util.Optional;
import java.util.UUID;
import ru.itmo.highload.catering.order.application.dto.in.KitchenCommand;
import ru.itmo.highload.catering.order.application.dto.out.OrderResponse;

public interface CommandReceipts {
    record Receipt(
            UUID orderId,
            String action,
            String expectedStatus,
            long expectedVersion,
            OrderResponse response) {}

    void lock(UUID commandId);

    Optional<Receipt> find(UUID commandId);

    void save(UUID orderId, KitchenCommand command, OrderResponse response);
}
