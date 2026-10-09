package ru.itmo.highload.catering.identity.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import ru.itmo.highload.catering.config.ExchangeProperties;
import ru.itmo.highload.common.dto.out.InternalTokenResponse;
import ru.itmo.highload.common.security.InternalJwt;
import ru.itmo.highload.common.security.InternalJwtProperties;

@Service
public class InternalTokenService {
    private final InternalJwtProperties properties;
    private final JwtEncoder encoder;

    public InternalTokenService(InternalJwtProperties properties, ExchangeProperties exchange) {
        this.properties = properties;
        try {
            var publicKey = InternalJwt.publicKey(properties.publicKeyBase64());
            var privateKey = (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.getDecoder().decode(exchange.privateKeyBase64())));
            if (!publicKey.getModulus().equals(privateKey.getModulus())) throw new IllegalArgumentException();
            var key = new RSAKey.Builder(publicKey).privateKey(privateKey).build();
            encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
        } catch (Exception invalid) { throw new IllegalArgumentException("Invalid internal JWT signing key pair"); }
    }

    public InternalTokenResponse issue(Jwt external) {
        // Internal tokens cannot be exchanged or renewed. Their lifetime belongs to one admission.
        if (!"HS256".equals(external.getHeaders().get("alg"))) throw new BadCredentialsException("Invalid token type");
        Instant now = Instant.now();
        Instant expires = now.plus(properties.ttl());
        if (external.getExpiresAt().isBefore(expires)) expires = external.getExpiresAt();
        if (!expires.isAfter(now)) throw new BadCredentialsException("Expired token");
        var claims = JwtClaimsSet.builder().issuer(properties.issuer()).audience(InternalJwt.AUDIENCES)
                .subject(external.getSubject()).issuedAt(now).expiresAt(expires)
                .id(UUID.randomUUID().toString()).claim("roles", external.getClaimAsStringList("roles"))
                .claim("ver", external.getClaim("ver"));
        String organization = external.getClaimAsString("organizationId");
        if (organization != null) claims.claim("organizationId", organization);
        String token = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).type(InternalJwt.TYPE).build(), claims.build())).getTokenValue();
        return new InternalTokenResponse(token, "Bearer", expires);
    }

    public String downstreamBearer(Jwt actor) {
        return "Bearer " + (InternalJwt.TYPE.equals(actor.getHeaders().get("typ"))
                ? actor.getTokenValue() : issue(actor).accessToken());
    }
}
