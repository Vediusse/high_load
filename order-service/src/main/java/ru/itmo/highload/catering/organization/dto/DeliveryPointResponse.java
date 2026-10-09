package ru.itmo.highload.catering.organization.dto;

import java.util.UUID;

public record DeliveryPointResponse(
        UUID id,
        UUID organizationId,
        String name,
        String address,
        String contactName,
        String contactPhone,
        boolean active) {
}
