package ru.itmo.highload.catering.identity.domain;
import java.util.Set;
import java.util.UUID;
import ru.itmo.highload.catering.identity.domain.model.RoleCode;
public record IdentityActor(UUID id, long version, Set<RoleCode> roles, UUID organizationId, String bearer) {
    public IdentityActor { roles = Set.copyOf(roles); }
}
