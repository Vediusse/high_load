package ru.itmo.highload.catering.kitchen.application.dto.out;

import java.util.UUID;
import ru.itmo.highload.catering.kitchen.domain.model.OrderStatus;

public record OrderState(UUID id, OrderStatus status, long version) {}
