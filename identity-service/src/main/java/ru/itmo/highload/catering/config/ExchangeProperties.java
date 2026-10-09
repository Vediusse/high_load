package ru.itmo.highload.catering.config;

import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("identity.exchange")
public record ExchangeProperties(String privateKeyBase64, String gatewaySecret) {
    public ExchangeProperties {
        try {
            if (Base64.getDecoder().decode(gatewaySecret == null ? "" : gatewaySecret).length < 32
                    || privateKeyBase64 == null || privateKeyBase64.isBlank()) throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Configure the exchange signing key and a random Gateway credential");
        }
    }
    @Override public String toString() { return "ExchangeProperties[redacted]"; }
}
