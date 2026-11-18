package ru.itmo.highload.catering.kitchen.domain.model;

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
