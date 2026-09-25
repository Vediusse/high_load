package ru.itmo.highload.catering.kitchen.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import ru.itmo.highload.catering.kitchen.client.dto.OrderStatus;

@Entity
@Table(name = "kitchen_task")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class KitchenTask {
    @Id
    private UUID orderId;
    @Enumerated(EnumType.STRING)
    private OrderStatus status;
    private long orderVersion;
    private Instant updatedAt;
    @Version
    private long version;
}
