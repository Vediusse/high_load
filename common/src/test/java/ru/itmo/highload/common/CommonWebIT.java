package ru.itmo.highload.common;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import ru.itmo.highload.common.dto.PageResponse;
import ru.itmo.highload.common.error.ApiException;

@SpringBootTest(classes = CommonWebIT.Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class CommonWebIT {
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(ExampleController.class)
    static class Application { }

    @Autowired WebTestClient client;

    @Test void propagatesTraceThroughHeadersAndReactorContext() {
        client.get().uri("/api/v1/trace").header("X-Trace-Id", "common-trace").exchange()
                .expectStatus().isOk().expectHeader().valueEquals("X-Trace-Id", "common-trace")
                .expectBody().jsonPath("$.items[0]").isEqualTo("common-trace");
        client.get().uri("/api/v1/trace").header("X-Trace-Id", "bad value!").exchange()
                .expectStatus().isOk().expectHeader().valueMatches("X-Trace-Id", "[a-f0-9-]{36}");
        client.get().uri("/api/v1/trace").exchange().expectStatus().isOk()
                .expectHeader().valueMatches("X-Trace-Id", "[a-f0-9-]{36}");
    }

    @Test void preservesDefaultErrorCodesAndStatusWithTrace() {
        Object[][] cases = {{"business",422,"RULE_FAILED"},{"busy",503,"SERVICE_BUSY"},
                {"integrity",409,"DATA_INTEGRITY_CONFLICT"},{"optimistic",409,"RESOURCE_VERSION_CONFLICT"},
                {"database",503,"DEPENDENCY_UNAVAILABLE"},{"query-timeout",503,"DEPENDENCY_UNAVAILABLE"},
                {"transaction-timeout",503,"DEPENDENCY_UNAVAILABLE"},
                {"transaction-unavailable",503,"DEPENDENCY_UNAVAILABLE"},
                {"unexpected",500,"INTERNAL_ERROR"}};
        for (Object[] test : cases) {
            client.get().uri("/api/v1/errors/"+test[0]).header("X-Trace-Id", "error-trace").exchange()
                    .expectStatus().isEqualTo((int)test[1]).expectBody().jsonPath("$.code").isEqualTo(test[2])
                    .jsonPath("$.traceId").isEqualTo("error-trace");
        }
        client.get().uri("/missing").exchange().expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("RESOURCE_NOT_FOUND");
        client.post().uri("/api/v1/trace").exchange().expectStatus().isEqualTo(405)
                .expectBody().jsonPath("$.code").isEqualTo("HTTP_405");
    }

    @Test void validatesBodiesAndQueryParametersWithoutChangingWireShape() {
        client.post().uri("/api/v1/echo").bodyValue(Map.of("name", " ")).exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("VALIDATION_FAILED").jsonPath("$.fieldErrors[0].field").isEqualTo("name");
        client.post().uri("/api/v1/echo").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue("{").exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("MALFORMED_JSON");
        client.get().uri("/api/v1/number?value=wrong").exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("VALIDATION_FAILED");
    }

    @RestController
    static class ExampleController {
        @GetMapping("/api/v1/trace")
        Mono<PageResponse<String>> trace() {
            return Mono.deferContextual(context -> Mono.just(new PageResponse<>(List.of(context.<String>get("traceId")),0,20,false)));
        }
        @PostMapping("/api/v1/echo")
        Input echo(@Valid @RequestBody Input input) { return input; }
        @GetMapping("/api/v1/number")
        int number(@RequestParam int value) { return value; }
        @GetMapping("/api/v1/errors/{kind}")
        void error(@PathVariable String kind) {
            throw switch (kind) {
                case "business" -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"RULE_FAILED","Test rule");
                case "busy" -> new RejectedExecutionException();
                case "integrity" -> new DataIntegrityViolationException("test constraint");
                case "optimistic" -> new OptimisticLockingFailureException("test version");
                case "database" -> new org.springframework.dao.DataAccessResourceFailureException("database down");
                case "query-timeout" -> new org.springframework.dao.QueryTimeoutException("query timeout");
                case "transaction-timeout" -> new org.springframework.transaction.TransactionTimedOutException("transaction timeout");
                case "transaction-unavailable" -> new org.springframework.transaction.CannotCreateTransactionException(
                        "Could not open EntityManager", new java.sql.SQLTransientConnectionException("database down"));
                default -> new IllegalStateException("test unexpected");
            };
        }
    }
    record Input(@NotBlank String name) { }
}
