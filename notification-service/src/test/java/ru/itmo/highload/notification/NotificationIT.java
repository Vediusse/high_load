package ru.itmo.highload.notification;
import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import ru.itmo.highload.common.domain.events.*;
import ru.itmo.highload.common.dto.out.PageResponse;
import ru.itmo.highload.notification.application.NotificationService;
import ru.itmo.highload.notification.adapters.out.identity.IdentityClient;

@org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.cloud.config.enabled=false","eureka.client.enabled=false","catering.events.enabled=false", "identity.notifications.secret=test-notification-service-credential-42"})
class NotificationIT {
    static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:17.11-alpine3.24");
    static { DB.start(); }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        ru.itmo.highload.common.security.TestTokens.register(p);
        p.add("spring.datasource.url",DB::getJdbcUrl);p.add("spring.datasource.username",DB::getUsername);p.add("spring.datasource.password",DB::getPassword);
    }
    @Autowired org.springframework.test.web.reactive.server.WebTestClient http;
    @Autowired NotificationService service;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean IdentityClient identity;
    @BeforeEach void clear() { jdbc.execute("truncate notification,processed_event"); }
    EventEnvelope event() { return new EventEnvelope(UUID.randomUUID(),EventType.ORDER_CONFIRMED,UUID.randomUUID(),Instant.now(),1,EventEnvelope.Payload.order(UUID.randomUUID(),4,UUID.randomUUID())); }
    @Test void duplicateAndConcurrentDeliveryHaveOneEffectAndEffectsRollBackTogether() throws Exception {
        var event=event();var recipient=UUID.randomUUID();
        org.mockito.Mockito.when(identity.recipients(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyInt(),org.mockito.ArgumentMatchers.eq(50),org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(new PageResponse<>(List.of(recipient),0,50,false));
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            for(var f:executor.invokeAll(List.of(java.util.concurrent.Executors.callable(()->service.consume(event)),java.util.concurrent.Executors.callable(()->service.consume(event))))) f.get();
        }
        assertThat(jdbc.queryForObject("select count(*) from notification",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from processed_event",Integer.class)).isEqualTo(1);
        jdbc.execute("alter table notification add constraint test_failure check(false) not valid");
        var failed=event();
        try { assertThatThrownBy(()->service.consume(failed)).isInstanceOf(RuntimeException.class); }
        finally { jdbc.execute("alter table notification drop constraint test_failure"); }
        assertThat(jdbc.queryForObject("select count(*) from processed_event where event_id=?",Integer.class,failed.eventId())).isZero();
        service.consume(failed);
        assertThat(jdbc.queryForObject("select count(*) from notification",Integer.class)).isEqualTo(2);
    }
    @Test void recipientLookupIsPaginatedAndFailureDoesNotAcknowledgeEvent() {
        var event=event();var first=UUID.randomUUID();var second=UUID.randomUUID();
        org.mockito.Mockito.when(identity.recipients("ORGANIZATION_REPRESENTATIVE",event.payload().organizationId(),0,50,"test-notification-service-credential-42"))
            .thenReturn(new PageResponse<>(List.of(first),0,50,true));
        org.mockito.Mockito.when(identity.recipients("ORGANIZATION_REPRESENTATIVE",event.payload().organizationId(),1,50,"test-notification-service-credential-42"))
            .thenThrow(new IllegalStateException("Identity unavailable"));
        assertThatThrownBy(()->service.consume(event)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select count(*) from processed_event",Integer.class)).isZero();
        org.mockito.Mockito.doReturn(new PageResponse<>(List.of(first,second),1,50,false)).when(identity)
            .recipients("ORGANIZATION_REPRESENTATIVE",event.payload().organizationId(),1,50,"test-notification-service-credential-42");
        service.consume(event);
        assertThat(jdbc.queryForObject("select count(*) from notification",Integer.class)).isEqualTo(2);
    }
    @Test void everyApprovedEventHasTheExpectedRecipientSetAndDuplicateIsIgnored() {
        UUID shared=UUID.randomUUID(),kitchen=UUID.randomUUID(),client=UUID.randomUUID(),representative=UUID.randomUUID();
        var groups=Map.of("KITCHEN_MANAGER",List.of(shared,kitchen),"CLIENT_MANAGER",List.of(shared,client),
                "ORGANIZATION_REPRESENTATIVE",List.of(shared,representative));
        org.mockito.Mockito.when(identity.recipients(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.eq(0),org.mockito.ArgumentMatchers.eq(50),org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(call -> new PageResponse<>(groups.get(call.getArgument(0)),0,50,false));
        for(var type:EventType.values()) {
            var payload=new EventEnvelope.Payload(UUID.randomUUID(),9,null,UUID.randomUUID(),"SPECIFICATION",0L);
            var event=new EventEnvelope(UUID.randomUUID(),type,UUID.randomUUID(),Instant.now(),1,payload);
            service.consume(event);service.consume(event);
            Set<UUID> expected=switch(type) {
                case ORDER_SUBMITTED -> Set.of(shared,kitchen);
                case ORDER_CANCELLED,FILE_UPLOADED,FILE_DELETED -> Set.of(shared,kitchen,client,representative);
                default -> Set.of(shared,representative);
            };
            var recipients=jdbc.queryForList("select recipient_user_id from notification where event_id=?",UUID.class,event.eventId());
            assertThat(recipients).containsExactlyInAnyOrderElementsOf(expected);
            assertThat(jdbc.queryForObject("select count(*) from processed_event where event_id=?",Integer.class,event.eventId())).isEqualTo(1);
        }
    }
    @Test void ownFeedIsPaginatedAndReadingAnotherUsersNotificationIsHidden() {
        var own=UUID.fromString(ru.itmo.highload.common.security.TestTokens.SUBJECT);var other=UUID.randomUUID();
        org.mockito.Mockito.when(identity.recipients(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyInt(),org.mockito.ArgumentMatchers.eq(50),org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new PageResponse<>(List.of(own,other),0,50,false));
        service.consume(event());service.consume(event());
        String bearer=ru.itmo.highload.common.security.TestTokens.bearerWithRoles("CLIENT_MANAGER");
        http.get().uri("/api/v1/notifications").exchange().expectStatus().isUnauthorized();
        http.get().uri("/api/v1/notifications?size=1").header("Authorization",bearer).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.items.length()").isEqualTo(1).jsonPath("$.hasNext").isEqualTo(true)
                .jsonPath("$.items[0].recipientUserId").doesNotExist();
        http.get().uri("/api/v1/notifications?page=1&size=1").header("Authorization",bearer).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.items.length()").isEqualTo(1).jsonPath("$.hasNext").isEqualTo(false);
        http.get().uri("/api/v1/notifications?size=51").header("Authorization",bearer).exchange().expectStatus().isBadRequest();
        http.get().uri("/api/v1/notifications?page=-1").header("Authorization",bearer).exchange().expectStatus().isBadRequest();
        UUID ownId=jdbc.queryForList("select id from notification where recipient_user_id=?",UUID.class,own).getFirst();
        String path="/api/v1/notifications/"+ownId+"/read";
        var first=http.post().uri(path).header("Authorization",bearer).exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        var again=http.post().uri(path).header("Authorization",bearer).exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        assertThat(again).isEqualTo(first);
        UUID foreignId=jdbc.queryForList("select id from notification where recipient_user_id=?",UUID.class,other).getFirst();
        http.post().uri("/api/v1/notifications/"+foreignId+"/read").header("Authorization",bearer).exchange().expectStatus().isNotFound();
        assertThat(jdbc.queryForObject("select read_at is null from notification where id=?",Boolean.class,foreignId)).isTrue();
        http.get().uri("/v3/api-docs").exchange().expectStatus().isOk().expectBody().jsonPath("$.components.securitySchemes.bearerAuth.scheme").isEqualTo("bearer").jsonPath("$.servers[0].url").isEqualTo("/");
    }

}
