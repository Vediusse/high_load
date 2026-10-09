package ru.itmo.highload.catering.order.entity;

public enum OrderStatus {
    DRAFT,
    SUBMITTED,
    CONFIRMED,
    REJECTED,
    CANCELLED,
    IN_COOKING,
    READY,
    COMPLETED
}
