package ru.itmo.highload.common.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("catering.security.internal")
public record InternalJwtProperties(
        String publicKeyBase64,
        @DefaultValue("corporate-catering-internal") String issuer,
        @DefaultValue("30s") Duration ttl,
        @DefaultValue("2s") Duration clockSkew) {
    public InternalJwtProperties {
        if (publicKeyBase64 == null || publicKeyBase64.isBlank() || issuer == null || issuer.isBlank()
                || ttl == null || ttl.compareTo(Duration.ofSeconds(5)) < 0
                || ttl.compareTo(Duration.ofSeconds(60)) > 0 || clockSkew == null
                || clockSkew.isNegative() || clockSkew.compareTo(Duration.ofSeconds(5)) > 0) {
            throw new IllegalArgumentException("Invalid internal JWT settings");
        }
    }
}
