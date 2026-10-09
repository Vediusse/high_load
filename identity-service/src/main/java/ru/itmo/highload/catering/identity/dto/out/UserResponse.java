package ru.itmo.highload.catering.identity.dto.out;

import java.util.Set;
import java.util.UUID;
import ru.itmo.highload.catering.identity.entity.RoleCode;
import ru.itmo.highload.catering.identity.entity.UserStatus;

public record UserResponse(
        UUID id,
        String login,
        UserStatus status,
        Set<RoleCode> roles,
        UUID organizationId) {
}
