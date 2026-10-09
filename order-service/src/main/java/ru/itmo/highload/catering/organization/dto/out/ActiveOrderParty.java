package ru.itmo.highload.catering.organization.dto.out;

import java.util.UUID;

public record ActiveOrderParty(UUID organizationId, UUID deliveryPointId) {
}
