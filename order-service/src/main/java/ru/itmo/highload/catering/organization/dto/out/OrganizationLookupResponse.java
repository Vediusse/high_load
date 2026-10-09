package ru.itmo.highload.catering.organization.dto.out;

import java.util.UUID;

public record OrganizationLookupResponse(UUID id, boolean active) { }
