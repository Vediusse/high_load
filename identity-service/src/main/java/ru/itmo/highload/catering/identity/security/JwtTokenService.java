package ru.itmo.highload.catering.identity.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.stereotype.Service;
import ru.itmo.highload.catering.config.JwtProperties;
import ru.itmo.highload.catering.identity.dto.out.TokenResponse;
import ru.itmo.highload.catering.identity.entity.AppUser;
import ru.itmo.highload.catering.identity.entity.RoleCode;

@Service
public class JwtTokenService {
    public static final String AUDIENCE = "corporate-catering-api";
    private final JwtProperties properties;
    private final JwtEncoder encoder;
    private final NimbusReactiveJwtDecoder decoder;

    public JwtTokenService(JwtProperties properties) {
        this.properties = properties;
        byte[] secret = Base64.getDecoder().decode(properties.secretBase64());
        encoder = new NimbusJwtEncoder(new ImmutableSecret<>(secret));
        decoder =
                NimbusReactiveJwtDecoder.withSecretKey(new SecretKeySpec(secret, "HmacSHA256"))
                        .macAlgorithm(MacAlgorithm.HS256)
                        .build();
        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(
                        new JwtTimestampValidator(properties.clockSkew()),
                        new JwtIssuerValidator(properties.issuer()),
                        this::claims));
    }

    public ReactiveJwtDecoder decoder() {
        return decoder;
    }

    public TokenResponse issue(AppUser user) {
        Instant now = Instant.now();
        Instant expires = now.plus(properties.accessTtl());
        var claims =
                JwtClaimsSet.builder()
                        .issuer(properties.issuer())
                        .audience(List.of(AUDIENCE))
                        .subject(user.getId().toString())
                        .issuedAt(now)
                        .expiresAt(expires)
                        .id(UUID.randomUUID().toString())
                        .claim("roles", user.roleCodes().stream().map(Enum::name).sorted().toList())
                        .claim("ver", user.getVersion());
        if (user.effectiveOrganizationId() != null) {
            claims.claim("organizationId", user.effectiveOrganizationId().toString());
        }
        var header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        return new TokenResponse(
                encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue(),
                "Bearer",
                expires);
    }

    private OAuth2TokenValidatorResult claims(Jwt jwt) {
        try {
            Object type = jwt.getHeaders().get("typ");
            if (!"HS256".equals(jwt.getHeaders().get("alg")) || (type != null && !"JWT".equals(type))) {
                throw new IllegalArgumentException();
            }
            UUID.fromString(jwt.getSubject());
            UUID.fromString(jwt.getId());
            if (!jwt.getAudience().contains(AUDIENCE)
                    || jwt.getIssuedAt() == null
                    || jwt.getExpiresAt() == null
                    || !jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
                    || jwt.getIssuedAt().isAfter(Instant.now().plus(properties.clockSkew()))) {
                throw new IllegalArgumentException();
            }
            Object version = jwt.getClaim("ver");
            if (!(version instanceof Long || version instanceof Integer)
                    || ((Number) version).longValue() < 0) {
                throw new IllegalArgumentException();
            }
            Set<RoleCode> roles = roles(jwt);
            Object organization = jwt.getClaim("organizationId");
            if (roles.contains(RoleCode.ORGANIZATION_REPRESENTATIVE)) {
                UUID.fromString((String) organization);
            } else if (organization != null) {
                throw new IllegalArgumentException();
            }
            return OAuth2TokenValidatorResult.success();
        } catch (RuntimeException invalid) {
            return OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "Invalid token claims", null));
        }
    }

    public static Set<RoleCode> roles(Jwt jwt) {
        Object raw = jwt.getClaim("roles");
        if (!(raw instanceof List<?> values)
                || values.isEmpty()
                || values.stream().anyMatch(value -> !(value instanceof String))) {
            throw new IllegalArgumentException("Invalid roles");
        }
        Set<RoleCode> roles = EnumSet.noneOf(RoleCode.class);
        for (Object value : values) {
            if (!roles.add(RoleCode.valueOf((String) value))) {
                throw new IllegalArgumentException("Duplicate role");
            }
        }
        return roles;
    }
}
