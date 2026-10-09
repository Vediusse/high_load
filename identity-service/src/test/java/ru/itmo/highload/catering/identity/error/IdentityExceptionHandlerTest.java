package ru.itmo.highload.catering.identity.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

class IdentityExceptionHandlerTest {
    @Test
    void unnamedConstraintDoesNotTurnConflictIntoInternalError() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/users"));
        var error = new DataIntegrityViolationException("conflict", new SQLException());

        var response = new IdentityExceptionHandler().conflict(error, exchange);

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.code()).isEqualTo("DATA_INTEGRITY_CONFLICT");
    }
}
