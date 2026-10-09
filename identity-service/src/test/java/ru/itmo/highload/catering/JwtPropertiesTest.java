package ru.itmo.highload.catering;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import ru.itmo.highload.catering.config.JwtProperties;

class JwtPropertiesTest {
    private final String secret = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void rejectsMissingShortAndInvalidKeyWithoutPrintingIt() {
        for (String key :
                new String[] {
                    null, "", Base64.getEncoder().encodeToString(new byte[31]), "not-base64!"
                })
            assertThatThrownBy(
                            () ->
                                    new JwtProperties(
                                            key,
                                            "issuer",
                                            Duration.ofMinutes(15),
                                            Duration.ofSeconds(30)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining("not-base64!");
    }

    @Test
    void rejectsInvalidLifetimesAndIssuer() {
        for (Duration ttl : new Duration[] {null, Duration.ZERO, Duration.ofSeconds(-1)})
            assertThatThrownBy(() -> new JwtProperties(secret, "issuer", ttl, Duration.ZERO))
                    .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> new JwtProperties(secret, " ", Duration.ofMinutes(15), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new JwtProperties(
                                        secret,
                                        "issuer",
                                        Duration.ofMinutes(15),
                                        Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(
                        new JwtProperties(secret, "issuer", Duration.ofMinutes(15), Duration.ZERO)
                                .toString())
                .doesNotContain(secret);
    }
}
