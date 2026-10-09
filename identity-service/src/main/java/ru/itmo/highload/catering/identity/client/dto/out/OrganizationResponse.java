package ru.itmo.highload.catering.identity.client.dto.out;

import java.util.UUID;

public record OrganizationResponse(UUID id, Boolean active) {
}
