package ru.itmo.highload.catering.identity.adapters.in.rest;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.security.oauth2.jwt.Jwt;
import ru.itmo.highload.catering.identity.domain.IdentityActor;
import ru.itmo.highload.catering.identity.adapters.out.security.InternalTokenService;
import ru.itmo.highload.catering.identity.adapters.out.security.JwtTokenService;
@Component @RequiredArgsConstructor
public class IdentityActors {
    private final InternalTokenService tokens;
    public static IdentityActor claims(Jwt jwt) {
        String org = jwt.getClaimAsString("organizationId");
        Number version = jwt.getClaim("ver");
        return new IdentityActor(UUID.fromString(jwt.getSubject()), version.longValue(),
                JwtTokenService.roles(jwt), org == null ? null : UUID.fromString(org), null);
    }
    public IdentityActor from(Jwt jwt) {
        var claims = claims(jwt);
        return new IdentityActor(claims.id(), claims.version(), claims.roles(), claims.organizationId(),
                tokens.downstreamBearer(jwt));
    }
}
