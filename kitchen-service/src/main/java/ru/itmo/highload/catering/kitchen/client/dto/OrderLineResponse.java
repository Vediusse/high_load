package ru.itmo.highload.catering.kitchen.client.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record OrderLineResponse(
        UUID id,
        UUID dishId,
        String dishNameSnapshot,
        int quantity,
        BigDecimal unitPriceSnapshot,
        BigDecimal lineAmount) {
}
