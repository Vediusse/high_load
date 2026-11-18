package ru.itmo.highload.common.domain.events;

public enum EventType {
    ORDER_SUBMITTED, ORDER_CONFIRMED, ORDER_REJECTED, ORDER_CANCELLED, ORDER_COMPLETED,
    COOKING_STARTED, ORDER_READY, FILE_UPLOADED, FILE_DELETED;

    public String topic() {
        return switch (this) {
            case COOKING_STARTED, ORDER_READY -> "production-events";
            case FILE_UPLOADED, FILE_DELETED -> "file-events";
            default -> "order-events";
        };
    }
}
