package ru.itmo.highload.catering.identity.adapters.out.feign.dto.out;

import java.util.UUID;

public record OrganizationResponse(UUID id, Boolean active) {}
