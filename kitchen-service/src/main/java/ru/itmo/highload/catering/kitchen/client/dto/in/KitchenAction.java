package ru.itmo.highload.catering.kitchen.client.dto.in;

import ru.itmo.highload.catering.kitchen.client.dto.out.OrderStatus;

public enum KitchenAction {
    START_COOKING(OrderStatus.CONFIRMED), MARK_READY(OrderStatus.IN_COOKING), COMPLETE(OrderStatus.READY);

    private final OrderStatus expectedStatus;
    KitchenAction(OrderStatus expectedStatus) { this.expectedStatus = expectedStatus; }
    public OrderStatus expectedStatus() { return expectedStatus; }
}
