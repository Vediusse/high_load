package ru.itmo.highload.catering.kitchen.client.dto.out;

import java.util.UUID;

public record OrderState(UUID id, OrderStatus status, long version) { }
