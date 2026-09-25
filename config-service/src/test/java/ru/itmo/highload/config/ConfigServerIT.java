package ru.itmo.highload.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        useMainMethod = SpringBootTest.UseMainMethod.ALWAYS)
class ConfigServerIT {
    @Autowired TestRestTemplate http;

    @Test void servesSharedAndApplicationConfiguration() {
        var result = http.getForEntity("/order-service/default", String.class);
        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(result.getBody()).contains("spring.datasource.url", "info.configuration.source", "config-service");
        assertThat(http.getForObject("/gateway-service/default", String.class))
                .contains("lb://order-service", "spring.cloud.gateway.server.webflux.routes");
    }
}
