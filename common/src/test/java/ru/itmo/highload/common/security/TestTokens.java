package ru.itmo.highload.common.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.DynamicPropertyRegistry;

public final class TestTokens {
    public static final KeyPair KEYS;
    public static final String GATEWAY_SECRET = Base64.getEncoder().encodeToString(new byte[32]);
    public static final String SUBJECT = UUID.randomUUID().toString();
    static {
        try {
            var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
            KEYS = generator.generateKeyPair();
        } catch (Exception error) { throw new ExceptionInInitializerError(error); }
    }
    private TestTokens() { }
    public static String publicKey() { return Base64.getEncoder().encodeToString(KEYS.getPublic().getEncoded()); }
    public static String privateKey() { return Base64.getEncoder().encodeToString(KEYS.getPrivate().getEncoded()); }
    public static void register(DynamicPropertyRegistry properties) {
        properties.add("catering.security.internal.enabled", () -> true);
        properties.add("catering.security.internal.public-key-base64", TestTokens::publicKey);
        properties.add("identity.exchange.private-key-base64", TestTokens::privateKey);
        properties.add("identity.exchange.gateway-secret", () -> GATEWAY_SECRET);
    }
    public static JwtClaimsSet.Builder claims() {
        Instant now = Instant.now();
        return JwtClaimsSet.builder().issuer("corporate-catering-internal").audience(InternalJwt.AUDIENCES)
                .subject(SUBJECT).id(UUID.randomUUID().toString()).issuedAt(now).expiresAt(now.plusSeconds(30))
                .claim("ver", 0L).claim("roles", List.of("SUPERVISOR", "CLIENT_MANAGER", "KITCHEN_MANAGER"));
    }
    public static String token(JwtClaimsSet claims) {
        var key = new RSAKey.Builder((RSAPublicKey) KEYS.getPublic()).privateKey((RSAPrivateKey) KEYS.getPrivate()).build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key))).encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).type(InternalJwt.TYPE).build(), claims)).getTokenValue();
    }
    public static String token() { return token(claims().build()); }
    public static String bearer() { return "Bearer " + token(); }
    public static String bearerWithRoles(String... roles) {
        return "Bearer " + token(claims().claim("roles", List.of(roles)).build());
    }
    public static String bearerForOrganization(UUID organizationId, String... additionalRoles) {
        var roles = new java.util.ArrayList<String>();
        roles.add("ORGANIZATION_REPRESENTATIVE");
        roles.addAll(List.of(additionalRoles));
        return "Bearer " + token(claims().claim("roles", roles).claim("organizationId", organizationId.toString()).build());
    }
}
