package ru.itmo.highload.catering.order.adapters.in.rest;

import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import ru.itmo.highload.catering.order.application.OrderAccess;
public final class OrderActor {
    private OrderActor() { }
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

}
