package ru.itmo.highload.catering;

import com.fasterxml.jackson.databind.JsonNode;
import feign.RequestInterceptor;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import reactor.core.scheduler.Schedulers;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
@Import(ReactiveExecutionIT.Observation.class)
class ReactiveExecutionIT extends AbstractPostgresIT {
    static final List<Call> sqlCalls = new CopyOnWriteArrayList<>();
    static final List<Call> feignCalls = new CopyOnWriteArrayList<>();
    record Call(String thread, boolean nonBlocking, boolean transaction, String trace) {
        static Call current() {
            return new Call(Thread.currentThread().getName(), Schedulers.isInNonBlockingThread(),
                    TransactionSynchronizationManager.isActualTransactionActive(), org.slf4j.MDC.get("traceId"));
        }
    }
    @TestConfiguration
    static class Observation {
        @Bean HibernatePropertiesCustomizer sqlThreads() {
            return properties -> properties.put("hibernate.session_factory.statement_inspector",
                    (org.hibernate.resource.jdbc.spi.StatementInspector) sql -> {
                        sqlCalls.add(Call.current());
                        return sql;
                    });
        }
        @Bean RequestInterceptor feignThreads() {
            return request -> feignCalls.add(Call.current());
        }
    }
    @Autowired WebTestClient client;
    @Autowired org.springframework.context.ApplicationContext context;

    @Test void httpJpaAndFeignRunOnBlockingWorkersWithTransactionAndTrace() {
        assertThat(context).isInstanceOf(org.springframework.boot.web.reactive.context.ReactiveWebServerApplicationContext.class);
        sqlCalls.clear(); feignCalls.clear();
        JsonNode org = post("/api/v1/organizations", Map.of("name", "Workers", "phone", "+79991234567"));
        JsonNode point = post("/api/v1/organizations/" + org.get("id").asText() + "/delivery-points",
                Map.of("name", "Office", "address", "Address", "contactName", "Anna", "contactPhone", "+79991234568"));
        JsonNode draft = post("/api/v1/orders", Map.of("organizationId", org.get("id").asText(),
                "deliveryPointId", point.get("id").asText(), "requestedDeliveryAt", "2099-01-01T12:00:00Z"));
        var dish = catalog.dish("Soup", "180.00");
        client.put().uri("/api/v1/orders/" + draft.get("id").asText() + "/lines")
                .header("X-Trace-Id", "reactive-request")
                .bodyValue(Map.of("expectedVersion", draft.get("version").asLong(),
                        "lines", List.of(Map.of("dishId", dish.getId(), "quantity", 2))))
                .exchange().expectStatus().isOk().expectHeader().valueEquals("X-Trace-Id", "reactive-request");
        assertThat(sqlCalls).isNotEmpty().allSatisfy(this::assertWorker);
        assertThat(feignCalls).hasSize(1).allSatisfy(this::assertWorker);
        assertThat(catalog.lastTrace).isEqualTo("reactive-request");
    }
    private void assertWorker(Call call) {
        assertThat(call.thread()).startsWith("order-blocking-");
        assertThat(call.nonBlocking()).isFalse();
        assertThat(call.transaction()).isTrue();
        assertThat(call.trace()).isEqualTo("reactive-request");
    }
    private JsonNode post(String path, Object body) {
        return client.post().uri(path).header("X-Trace-Id", "reactive-request").bodyValue(body)
                .exchange().expectStatus().isCreated().expectBody(JsonNode.class).returnResult().getResponseBody();
    }
}
