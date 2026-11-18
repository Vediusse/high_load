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

@SpringBootTest(properties={"spring.cloud.config.enabled=false","eureka.client.enabled=false","catering.events.enabled=false", "identity.notifications.secret=test-notification-service-credential-42"})
class NotificationIT {
    static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:17.11-alpine3.24");
    static { DB.start(); }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        ru.itmo.highload.common.security.TestTokens.register(p);
        p.add("spring.datasource.url",DB::getJdbcUrl);p.add("spring.datasource.username",DB::getUsername);p.add("spring.datasource.password",DB::getPassword);
    }
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
}
