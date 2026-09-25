package ru.itmo.highload.catering.production.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import ru.itmo.highload.catering.order.entity.OrderStatus;

@Entity
@Table(name = "production_task")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class ProductionTask {
    @Id
    private UUID orderId;
    @Enumerated(EnumType.STRING)
    private OrderStatus status;
    private long orderVersion;
    private Instant updatedAt;
    @Version
    private long version;
}
