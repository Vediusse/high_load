package ru.itmo.highload.catering.organization.dto;

import java.util.UUID;

public record ActiveOrderParty(UUID organizationId, UUID deliveryPointId) {
}
