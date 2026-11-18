package ru.itmo.highload.catering.kitchen.domain.model;

import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import ru.itmo.highload.catering.kitchen.domain.model.OrderStatus;


@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class KitchenTask {

    private UUID orderId;

    private OrderStatus status;
    private long orderVersion;
    private Instant updatedAt;

    private long version;
}
