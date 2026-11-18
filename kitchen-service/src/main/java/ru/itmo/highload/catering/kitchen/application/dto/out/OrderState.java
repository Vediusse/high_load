package ru.itmo.highload.catering.kitchen.application.dto.out;
import ru.itmo.highload.catering.kitchen.domain.model.OrderStatus;

import java.util.UUID;

public record OrderState(UUID id, OrderStatus status, long version) { }
