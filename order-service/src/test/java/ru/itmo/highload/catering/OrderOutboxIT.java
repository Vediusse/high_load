package ru.itmo.highload.catering;
import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import ru.itmo.highload.catering.order.application.OrderService;
import ru.itmo.highload.catering.order.domain.model.OrderStatus;
@SpringBootTest
class OrderOutboxIT extends AbstractPostgresIT {
    @Autowired OrderService orders;
    @Test void confirmCommitsHistoryAndOutboxTogetherAndRollsBackOnOutboxFailure() {
        jdbcTemplate.execute("truncate outbox_event");
        UUID org=UUID.randomUUID(),point=UUID.randomUUID(),order=UUID.randomUUID();
        jdbcTemplate.update("insert into organization(id,name,phone,active,version) values (?,'Outbox','+79990000000',true,0)",org);
        jdbcTemplate.update("insert into delivery_point(id,organization_id,name,address,contact_name,contact_phone,active) values (?,?,'Office','Address','Contact','+79990000000',true)",point,org);
        jdbcTemplate.update("insert into corporate_order(id,organization_id,delivery_point_id,requested_delivery_at,status,total_amount,created_at,version) values (?,?,?,?,'SUBMITTED',0,?,0)",order,org,point,java.sql.Timestamp.from(Instant.now().plusSeconds(3600)),java.sql.Timestamp.from(Instant.now()));
        jdbcTemplate.execute("alter table outbox_event add constraint test_reject_outbox check (false) not valid");
        try {
            assertThatThrownBy(()->orders.confirm(order,0)).isInstanceOf(RuntimeException.class);
            assertThat(orders.getOrder(order).status()).isEqualTo(OrderStatus.SUBMITTED);
            assertThat(jdbcTemplate.queryForObject("select count(*) from order_status_history",Integer.class)).isZero();
        } finally { jdbcTemplate.execute("alter table outbox_event drop constraint test_reject_outbox"); }
        var confirmed=orders.confirm(order,0);
        assertThat(confirmed.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(jdbcTemplate.queryForObject("select count(*) from order_status_history",Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select payload->>'eventType' from outbox_event",String.class)).isEqualTo("ORDER_CONFIRMED");
        assertThat(jdbcTemplate.queryForObject("select payload->>'aggregateId' from outbox_event",String.class)).isEqualTo(order.toString());
        assertThat(jdbcTemplate.queryForObject("select sent_at is null from outbox_event",Boolean.class)).isTrue();
    }
}
