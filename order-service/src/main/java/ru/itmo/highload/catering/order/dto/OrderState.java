package ru.itmo.highload.catering.order.dto;

import java.util.UUID;
import ru.itmo.highload.catering.order.entity.OrderStatus;

public record OrderState(UUID id, OrderStatus status, long version) { }
