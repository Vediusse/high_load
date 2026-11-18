package ru.itmo.highload.catering.identity.application.dto.out;

import java.util.Set;
import java.util.UUID;
import ru.itmo.highload.catering.identity.domain.model.RoleCode;
import ru.itmo.highload.catering.identity.domain.model.UserStatus;

public record CurrentUserResponse(
        UUID id, String login, UserStatus status, Set<RoleCode> roles, UUID organizationId) {}
