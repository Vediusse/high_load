package ru.itmo.highload.catering;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.resilience4j.circuitbreaker.*;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import ru.itmo.highload.catering.kitchen.client.dto.OrderResponse;
import ru.itmo.highload.catering.kitchen.client.dto.OrderStatus;
import ru.itmo.highload.catering.kitchen.service.TaskProjection;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, useMainMethod=SpringBootTest.UseMainMethod.ALWAYS)
@org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
class KitchenIT {
    static final OrderFixture owner=new OrderFixture();
    static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:17.11-alpine3.24");
    static {postgres.start();}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",postgres::getUsername);
        r.add("spring.datasource.password",postgres::getPassword);r.add("clients.order.url",owner::url);
    }
    @Autowired WebTestClient client;
    @Autowired JdbcTemplate jdbc;
    @Autowired CircuitBreakerRegistry breakers;
    @Autowired TaskProjection projection;

    @BeforeEach void reset() {
        owner.reset();jdbc.execute("truncate kitchen_task");breakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
        client=client.mutate().responseTimeout(Duration.ofSeconds(10)).build();
    }

    @Test void lifecyclePreservesPublicContractTraceAndOriginalReplayWithoutRegressingProjection() {
        var order=owner.add(OrderStatus.CONFIRMED);
        var cooking=command(order,"start-cooking",200,"IN_COOKING");
        var ready=command(cooking,"mark-ready",200,"READY");
        var completed=command(ready,"complete",200,"COMPLETED");
        assertThat(command(order,"start-cooking",200,"IN_COOKING")).isEqualTo(cooking);
        assertThat(owner.transitions.get()).isEqualTo(3);
        assertTask(order.id(),"COMPLETED",completed.version());
        assertThat(completed.totalAmount()).isEqualByComparingTo("360");
        assertThat(completed.lines()).isEqualTo(order.lines());
        assertThat(owner.trace).isEqualTo("kitchen-check");
    }

    @Test void lostReplyCanBeRetriedWithoutDuplicateTransitionOrFalseLocalSuccess() {
        var order=owner.add(OrderStatus.CONFIRMED);queue(0,20,200);owner.mode="lost-reply";
        command(order,"start-cooking",503,null);assertTask(order.id(),"CONFIRMED",order.version());
        assertThat(owner.orders.get(order.id()).status()).isEqualTo(OrderStatus.IN_COOKING);
        var result=command(order,"start-cooking",200,"IN_COOKING");
        assertTask(order.id(),"IN_COOKING",result.version());assertThat(owner.transitions.get()).isEqualTo(1);
    }

    @Test void failedLocalSaveIsRepairableAndDoesNotRepeatOwnerTransition() {
        var order=owner.add(OrderStatus.CONFIRMED);queue(0,20,200);
        jdbc.execute("alter table kitchen_task add constraint test_no_cooking check (status <> 'IN_COOKING')");
        try {command(order,"start-cooking",503,null);assertTask(order.id(),"CONFIRMED",order.version());}
        finally {jdbc.execute("alter table kitchen_task drop constraint test_no_cooking");}
        var result=command(order,"start-cooking",200,"IN_COOKING");
        assertTask(order.id(),"IN_COOKING",result.version());assertThat(owner.transitions.get()).isEqualTo(1);
    }

    @Test void queueRecoversExistingCookingAndReadyTasksAndReconcilesCancellationAcrossPages() {
        var confirmed=owner.add(OrderStatus.CONFIRMED);var cooking=owner.add(OrderStatus.IN_COOKING);var ready=owner.add(OrderStatus.READY);
        var draft=owner.add(OrderStatus.DRAFT);
        for(int page=0;page<3;page++) queue(page,1,200);
        assertThat(jdbc.queryForObject("select count(*) from kitchen_task",Integer.class)).isEqualTo(3);
        var cancelled=owner.state(confirmed,OrderStatus.CANCELLED);
        var body=queue(0,1,200);assertThat(body.path("items").size()).isEqualTo(1);
        assertTask(cancelled.id(),"CANCELLED",cancelled.version());assertTask(cooking.id(),"IN_COOKING",cooking.version());
        assertTask(ready.id(),"READY",ready.version());
        assertThat(jdbc.queryForObject("select count(*) from kitchen_task where order_id=?",Integer.class,draft.id())).isZero();
    }

    @Test void breakerOpensWithoutCallingOwnerAndRecoversThroughHalfOpen() throws Exception {
        var order=owner.add(OrderStatus.CONFIRMED);queue(0,20,200);
        var breaker=breakers.circuitBreaker("order");breaker.reset();owner.mode="down";
        for(int i=0;i<5;i++)queue(0,20,503);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        int before=owner.calls.get();queue(0,20,503);command(order,"start-cooking",503,null);
        assertThat(owner.calls.get()).isEqualTo(before);assertTask(order.id(),"CONFIRMED",order.version());
        owner.mode="normal";Thread.sleep(5100);
        // queue performs the two permitted probes: page and bounded state batch.
        queue(0,20,200);assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test void reconciliationIsBoundedAndEventuallyChecksTasksOutsideTheRequestedPage() {
        var known = java.util.stream.IntStream.range(0, 120)
                .mapToObj(i -> owner.add(OrderStatus.CONFIRMED)).toList();
        known.forEach(projection::observe);
        known.forEach(order -> owner.state(order, OrderStatus.CANCELLED));
        owner.calls.set(0);
        for (int attempt = 0; attempt < 3; attempt++) {
            queue(0, 1, 200);
            assertThat(owner.calls.get()).isEqualTo((attempt + 1) * 2);
            assertThat(jdbc.queryForObject("select count(*) from kitchen_task where status='CANCELLED'", Integer.class))
                    .isEqualTo(Math.min((attempt + 1) * 50, 120));
        }
    }

    @Test void unchangedTasksDoNotStarveLaterTasksAndIncompleteBatchCannotUpdateProjection() {
        var known = java.util.stream.IntStream.range(0, 60)
                .mapToObj(i -> owner.add(OrderStatus.CONFIRMED)).toList();
        known.forEach(projection::observe);
        var cancelled = owner.state(known.getLast(), OrderStatus.CANCELLED);
        queue(0, 1, 200);
        queue(0, 1, 200);
        assertTask(cancelled.id(), "CANCELLED", cancelled.version());
        var another = owner.state(known.getFirst(), OrderStatus.CANCELLED);
        owner.mode = "incomplete-states";
        queue(0, 1, 503);
        assertTask(another.id(), "CONFIRMED", known.getFirst().version());
    }

    @Test void timeoutIsFiniteAndFeignDoesNotRetry() {
        owner.delay=2600;long start=System.nanoTime();queue(0,20,503);
        assertThat(Duration.ofNanos(System.nanoTime()-start)).isBetween(Duration.ofMillis(1700),Duration.ofSeconds(5));
        assertThat(owner.calls.get()).isEqualTo(1);
    }

    @Test void businessErrorsDoNotOpenBreakerOrCreateTasks() {
        var submitted=owner.add(OrderStatus.SUBMITTED);
        for(int i=0;i<7;i++) command(submitted,"start-cooking",409,null);
        assertThat(breakers.circuitBreaker("order").getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(jdbc.queryForObject("select count(*) from kitchen_task",Integer.class)).isZero();
        client.post().uri("/api/v1/orders/"+UUID.randomUUID()+"/start-cooking").bodyValue(Map.of("expectedVersion",0))
                .exchange().expectStatus().isNotFound().expectBody().jsonPath("$.code").isEqualTo("RESOURCE_NOT_FOUND");
    }

    @Test void malformedDependencyAndUnknownErrorNeverBecomeSuccess() {
        owner.mode="malformed";queue(0,20,503);owner.mode="bad-error";queue(0,20,503);
        assertThat(jdbc.queryForObject("select count(*) from kitchen_task",Integer.class)).isZero();
    }

    @Test void concurrentOldAndNewObservationsNeverRegressTask() throws Exception {
        var order=owner.add(OrderStatus.CONFIRMED);var newer=owner.state(order,OrderStatus.READY);
        try(var pool=Executors.newFixedThreadPool(4)) {
            var tasks=java.util.stream.IntStream.range(0,16).mapToObj(i -> pool.submit(() -> projection.observe(i%2==0?order:newer))).toList();
            for(var task:tasks)task.get(10,TimeUnit.SECONDS);
        }
        assertTask(order.id(),"READY",newer.version());
    }

    @Test void validationHealthAndSwaggerMatchTheThreePublicCommands() {
        client.get().uri("/actuator/health/readiness").exchange().expectStatus().isOk();
        client.get().uri("/swagger-ui/index.html").exchange().expectStatus().isOk();
        var docs=client.get().uri("/v3/api-docs").exchange().expectStatus().isOk().expectBody(JsonNode.class).returnResult().getResponseBody();
        assertThat(docs.path("paths").size()).isEqualTo(3);assertThat(docs.path("paths").toString()).doesNotContain("/internal/");
        String path="/api/v1/orders/"+UUID.randomUUID()+"/start-cooking";
        for(Object body:List.of(Map.of(),Map.of("expectedVersion",-1)))
            client.post().uri(path).bodyValue(body).exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("VALIDATION_FAILED");
        client.post().uri(path).contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue("{")
                .exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("MALFORMED_JSON");
        queue(0,51,400);queue(-1,20,400);
        client.get().uri("/internal/v1/kitchen/tasks?page=oops").exchange().expectStatus().isBadRequest();
    }

    private JsonNode queue(int page,int size,int status) {
        return client.get().uri("/internal/v1/kitchen/tasks?page="+page+"&size="+size).exchange()
                .expectStatus().isEqualTo(status).expectBody(JsonNode.class).returnResult().getResponseBody();
    }
    private OrderResponse command(OrderResponse order,String action,int status,String expected) {
        var response=client.post().uri("/api/v1/orders/"+order.id()+"/"+action).header("X-Trace-Id","kitchen-check")
                .bodyValue(Map.of("expectedVersion",order.version())).exchange().expectStatus().isEqualTo(status)
                .expectHeader().valueEquals("X-Trace-Id","kitchen-check");
        if(status!=200){response.expectBody().jsonPath("$.traceId").isEqualTo("kitchen-check");return null;}
        var result=response.expectBody(OrderResponse.class).returnResult().getResponseBody();
        assertThat(result.status().name()).isEqualTo(expected);return result;
    }
    private void assertTask(UUID id,String status,long version) {
        var task=jdbc.queryForMap("select status,order_version from kitchen_task where order_id=?",id);
        assertThat(task.get("status")).isEqualTo(status);assertThat(((Number)task.get("order_version")).longValue()).isEqualTo(version);
    }
}
