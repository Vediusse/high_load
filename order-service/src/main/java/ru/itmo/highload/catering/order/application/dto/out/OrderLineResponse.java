package ru.itmo.highload.catering.order.application.dto.out;

import java.math.BigDecimal;
import java.util.UUID;

public record OrderLineResponse(
        UUID id,
        UUID dishId,
        String dishNameSnapshot,
        int quantity,
        BigDecimal unitPriceSnapshot,
        BigDecimal lineAmount) {}
