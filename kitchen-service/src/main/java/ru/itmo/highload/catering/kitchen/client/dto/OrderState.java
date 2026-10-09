package ru.itmo.highload.catering.kitchen.client.dto;

import java.util.UUID;

public record OrderState(UUID id, OrderStatus status, long version) { }
