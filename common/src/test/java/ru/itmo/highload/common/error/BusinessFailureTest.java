package ru.itmo.highload.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import ru.itmo.highload.common.domain.BusinessFailure;

class BusinessFailureTest {
    @ParameterizedTest
    @CsvSource({
        "VALIDATION,400",
        "UNAUTHENTICATED,401",
        "FORBIDDEN,403",
        "NOT_FOUND,404",
        "CONFLICT,409",
        "REJECTED,422",
        "UNAVAILABLE,503"
    })
    void mapsUseCaseFailuresToTheExistingHttpContract(BusinessFailure.Kind kind, int status) {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/orders"));
        exchange.getAttributes().put("traceId", "failure-test");
        var body =
                new GlobalExceptionHandler()
                        .business(new BusinessFailure(kind, "TEST_CODE", "Message"), exchange);
        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(status);
        assertThat(body.code()).isEqualTo("TEST_CODE");
        assertThat(body.message()).isEqualTo("Message");
        assertThat(body.fieldErrors()).isEmpty();
        assertThat(body.traceId()).isEqualTo("failure-test");
        if (status == 401)
            assertThat(exchange.getResponse().getHeaders().getFirst("WWW-Authenticate"))
                    .isEqualTo("Bearer");
    }
}
