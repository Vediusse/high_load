package ru.itmo.highload.common.security;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Internal, short-lived proof of the user state checked at the request boundary. */
public final class InternalJwt {
    public static final String TYPE = "catering-internal+jwt";
    public static final List<String> AUDIENCES = List.of(
            "gateway-service", "identity-service", "catalog-service", "order-service", "kitchen-service");
    private static final Set<String> ROLES = Set.of(
            "SUPERVISOR", "CLIENT_MANAGER", "KITCHEN_MANAGER", "ORGANIZATION_REPRESENTATIVE");

    private InternalJwt() { }

    public static RSAPublicKey publicKey(String encoded) {
        try {
            var key = (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encoded)));
            if (key.getModulus().bitLength() < 2048) throw new IllegalArgumentException();
            return key;
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Internal JWT requires an RSA public key of at least 2048 bits");
        }
    }

    public static ReactiveJwtDecoder decoder(InternalJwtProperties properties, String audience) {
        var decoder = NimbusReactiveJwtDecoder.withPublicKey(publicKey(properties.publicKeyBase64()))
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .jwtProcessorCustomizer(processor -> processor.setJWSTypeVerifier(
                        new DefaultJOSEObjectTypeVerifier<>(new JOSEObjectType(TYPE))))
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtIssuerValidator(properties.issuer()),
                new JwtTimestampValidator(properties.clockSkew()),
                jwt -> validate(jwt, properties, audience)));
        return decoder;
    }

    private static OAuth2TokenValidatorResult validate(Jwt jwt, InternalJwtProperties properties, String audience) {
        try {
            if (!TYPE.equals(jwt.getHeaders().get("typ")) || !jwt.getAudience().contains(audience))
                throw new IllegalArgumentException();
            UUID.fromString(jwt.getSubject());
            UUID.fromString(jwt.getId());
            if (jwt.getIssuedAt() == null || jwt.getExpiresAt() == null
                    || !jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
                    || jwt.getExpiresAt().isAfter(jwt.getIssuedAt().plus(properties.ttl()))
                    || jwt.getIssuedAt().isAfter(Instant.now().plus(properties.clockSkew())))
                throw new IllegalArgumentException();
            Object version = jwt.getClaim("ver");
            if (!(version instanceof Long || version instanceof Integer) || ((Number) version).longValue() < 0)
                throw new IllegalArgumentException();
            Object value = jwt.getClaim("roles");
            if (!(value instanceof List<?> roles) || roles.isEmpty()
                    || roles.stream().anyMatch(role -> !(role instanceof String) || !ROLES.contains(role))
                    || new HashSet<>(roles).size() != roles.size()) throw new IllegalArgumentException();
            String organization = jwt.getClaimAsString("organizationId");
            if (roles.contains("ORGANIZATION_REPRESENTATIVE")) UUID.fromString(organization);
            else if (organization != null) throw new IllegalArgumentException();
            return OAuth2TokenValidatorResult.success();
        } catch (RuntimeException invalid) {
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        }
    }

    public static JwtAuthenticationToken authentication(Jwt jwt) {
        return new JwtAuthenticationToken(jwt, jwt.getClaimAsStringList("roles").stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList());
    }
}
