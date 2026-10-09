package ru.itmo.highload.catering.order.service;

import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

/** Immutable request facts needed by the blocking order use cases. */
public record OrderAccess(UUID userId, boolean clientManager, boolean organizationRepresentative, UUID organizationId) {

    public static OrderAccess from(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        boolean representative = roles.contains("ORGANIZATION_REPRESENTATIVE");
        String organization = jwt.getClaimAsString("organizationId");
        return new OrderAccess(
                UUID.fromString(jwt.getSubject()),
                roles.contains("CLIENT_MANAGER"),
                representative,
                representative ? UUID.fromString(organization) : null);
    }

    public boolean restrictsToOrganization() {
        return organizationRepresentative;
    }

    public boolean hidesDraftOrders() {
        return !clientManager && !organizationRepresentative;
    }
}
