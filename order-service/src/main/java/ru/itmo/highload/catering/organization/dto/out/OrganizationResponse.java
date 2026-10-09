package ru.itmo.highload.catering.organization.dto.out;

import java.util.UUID;

public record OrganizationResponse(UUID id, String name, String phone, boolean active, long version) {
}
