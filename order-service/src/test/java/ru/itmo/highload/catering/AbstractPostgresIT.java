package ru.itmo.highload.catering;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

abstract class AbstractPostgresIT {

    protected static final CatalogFixture catalog = new CatalogFixture();

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17.11-alpine3.24");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("clients.catalog.url", catalog::url);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry breakers;

    @BeforeEach
    void cleanBusinessTables() {
        catalog.reset();
        breakers.getAllCircuitBreakers().forEach(io.github.resilience4j.circuitbreaker.CircuitBreaker::reset);
        jdbcTemplate.execute("""
                TRUNCATE TABLE order_status_history, order_line, corporate_order,
                    delivery_point, organization CASCADE
                """);
    }
}
