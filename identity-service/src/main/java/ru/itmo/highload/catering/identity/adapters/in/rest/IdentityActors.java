package ru.itmo.highload.catering.identity.adapters.in.rest;

import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import ru.itmo.highload.catering.identity.application.ports.InternalTokens;
import ru.itmo.highload.catering.identity.domain.IdentityActor;
import ru.itmo.highload.catering.identity.domain.model.RoleCode;
import ru.itmo.highload.common.security.InternalJwt;

@Component
@RequiredArgsConstructor
public class IdentityActors {
    private final InternalTokens tokens;

    public static IdentityActor claims(Jwt jwt) {
        String org = jwt.getClaimAsString("organizationId");
        Number version = jwt.getClaim("ver");
        return new IdentityActor(
                UUID.fromString(jwt.getSubject()),
                version.longValue(),
                jwt.getClaimAsStringList("roles").stream()
                        .map(RoleCode::valueOf)
                        .collect(Collectors.toUnmodifiableSet()),
                org == null ? null : UUID.fromString(org),
                null);
    }

    public IdentityActor from(Jwt jwt) {
        var claims = claims(jwt);
        return new IdentityActor(
                claims.id(),
                claims.version(),
                claims.roles(),
                claims.organizationId(),
                "Bearer "
                        + (InternalJwt.TYPE.equals(jwt.getHeaders().get("typ"))
                                ? jwt.getTokenValue()
                                : tokens.issue(claims, jwt.getExpiresAt()).accessToken()));
    }
}
