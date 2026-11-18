package ru.itmo.highload.catering.organization.application.dto.out;

import java.util.UUID;

public record OrganizationLookupResponse(UUID id, boolean active) { }
