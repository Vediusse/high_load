package ru.itmo.highload.catering.order.application.dto.out;

import java.util.UUID;
import ru.itmo.highload.catering.order.domain.model.OrderStatus;

public record OrderState(UUID id, OrderStatus status, long version) { }
