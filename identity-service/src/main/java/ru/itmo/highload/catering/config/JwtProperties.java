package ru.itmo.highload.catering.config;

import java.time.Duration;
import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("identity.jwt")
public record JwtProperties(
        String secretBase64,
        @DefaultValue("corporate-catering-identity") String issuer,
        @DefaultValue("15m") Duration accessTtl,
        @DefaultValue("30s") Duration clockSkew) {
    public JwtProperties {
        byte[] secret;
        try {
            secret = Base64.getDecoder().decode(secretBase64 == null ? "" : secretBase64);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("JWT secret must be Base64");
        }
        if (secret.length < 32) {
            throw new IllegalArgumentException("JWT secret must contain at least 32 random bytes");
        }
        if (issuer == null
                || issuer.isBlank()
                || accessTtl == null
                || accessTtl.isNegative()
                || accessTtl.isZero()
                || clockSkew == null
                || clockSkew.isNegative()) {
            throw new IllegalArgumentException("Invalid JWT settings");
        }
    }

    @Override
    public String toString() {
        return "JwtProperties[redacted]";
    }
}
