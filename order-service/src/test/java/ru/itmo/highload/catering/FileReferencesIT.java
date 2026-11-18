package ru.itmo.highload.catering;
import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.test.web.reactive.server.WebTestClient;
import ru.itmo.highload.catering.order.application.FileReferences;
import ru.itmo.highload.common.domain.events.*;
import ru.itmo.highload.common.security.TestTokens;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient(timeout="20s")
class FileReferencesIT extends AbstractPostgresIT {
    @Autowired FileReferences links;
    @Autowired WebTestClient http;
    UUID org,order;
    @BeforeEach void fixture() {
        jdbcTemplate.execute("truncate processed_event,file_reference");
        org=UUID.randomUUID();UUID point=UUID.randomUUID();order=UUID.randomUUID();
        jdbcTemplate.update("insert into organization(id,name,phone,active,version) values (?,'Files','+79990000000',true,0)",org);
        jdbcTemplate.update("insert into delivery_point(id,organization_id,name,address,contact_name,contact_phone,active) values (?,?,'Office','Address','Contact','+79990000000',true)",point,org);
        jdbcTemplate.update("insert into corporate_order(id,organization_id,delivery_point_id,requested_delivery_at,status,total_amount,created_at,version) values (?,?,?,?,'CONFIRMED',0,?,0)",order,org,point,java.sql.Timestamp.from(Instant.now().plusSeconds(3600)),java.sql.Timestamp.from(Instant.now()));
    }
    EventEnvelope event(UUID id,EventType type,long version) {
        return new EventEnvelope(UUID.randomUUID(),type,order,Instant.now(),1,new EventEnvelope.Payload(org,0,null,id,"SPECIFICATION",version));
    }
    @Test void replayDeletionAndOutOfOrderUploadNeverResurrectADeletedLink() {
        UUID file=UUID.randomUUID();var upload=event(file,EventType.FILE_UPLOADED,0);
        links.consume(upload);links.consume(upload);
        assertThat(jdbcTemplate.queryForObject("select count(*) from file_reference",Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from processed_event",Integer.class)).isEqualTo(1);
        String path="/api/v1/orders/"+order+"/file-references";
        http.get().uri(path).header("Authorization",TestTokens.bearerForOrganization(org)).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.items[0].fileId").isEqualTo(file.toString());
        http.get().uri(path).header("Authorization",TestTokens.bearerForOrganization(UUID.randomUUID())).exchange().expectStatus().isNotFound();
        http.get().uri(path).header("Authorization",TestTokens.bearerWithRoles("SUPERVISOR")).exchange().expectStatus().isForbidden();
        http.get().uri(path+"?size=51").header("Authorization",TestTokens.bearerForOrganization(org)).exchange().expectStatus().isBadRequest();
        links.consume(event(file,EventType.FILE_DELETED,1));links.consume(event(file,EventType.FILE_UPLOADED,0));
        http.get().uri(path).header("Authorization",TestTokens.bearerForOrganization(org)).exchange().expectStatus().isOk().expectBody().jsonPath("$.items.length()").isEqualTo(0);
        assertThat(jdbcTemplate.queryForObject("select file_version from file_reference",Long.class)).isEqualTo(1);
    }
    @Test void failedProjectionRollsBackProcessedEventAndAccessEndpointEnforcesTenant() {
        var event=event(UUID.randomUUID(),EventType.FILE_UPLOADED,0);
        var wrong=new EventEnvelope(UUID.randomUUID(),EventType.FILE_UPLOADED,order,Instant.now(),1,
                new EventEnvelope.Payload(UUID.randomUUID(),0,null,UUID.randomUUID(),"SPECIFICATION",0L));
        assertThatThrownBy(()->links.consume(wrong)).isInstanceOf(IllegalArgumentException.class);
        jdbcTemplate.execute("alter table file_reference add constraint test_file_ref check(false) not valid");
        try { assertThatThrownBy(()->links.consume(event)).isInstanceOf(RuntimeException.class); }
        finally {jdbcTemplate.execute("alter table file_reference drop constraint test_file_ref");}
        assertThat(jdbcTemplate.queryForObject("select count(*) from processed_event",Integer.class)).isZero();
        links.consume(event);
        String path="/internal/v1/orders/"+order+"/file-access";
        http.get().uri(path).header("Authorization",TestTokens.bearerForOrganization(org)).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.organizationId").isEqualTo(org.toString()).jsonPath("$.status").isEqualTo("CONFIRMED");
        http.get().uri(path).header("Authorization",TestTokens.bearerForOrganization(UUID.randomUUID())).exchange().expectStatus().isNotFound();
        http.get().uri(path).header("Authorization",TestTokens.bearerWithRoles("SUPERVISOR")).exchange().expectStatus().isForbidden();
    }
}
