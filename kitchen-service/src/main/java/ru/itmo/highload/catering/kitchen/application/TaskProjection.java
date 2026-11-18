package ru.itmo.highload.catering.kitchen.application;

import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import ru.itmo.highload.catering.kitchen.application.dto.out.OrderResponse;
import ru.itmo.highload.catering.kitchen.application.dto.out.OrderState;
import ru.itmo.highload.catering.kitchen.application.ports.KitchenTaskStore;
import ru.itmo.highload.catering.kitchen.domain.model.KitchenObservation;
@RequiredArgsConstructor
public class TaskProjection {
    private final KitchenTaskStore tasks;
    private final ru.itmo.highload.common.domain.events.EventOutbox outbox;
    private final ru.itmo.highload.common.domain.events.EventInbox inbox;
    public void observe(OrderResponse order) {
        var observation=new KitchenObservation(order.id(),order.status(),order.version());
        if(observation.isCookingState() || tasks.existsById(order.id())) tasks.observe(observation);
    }
    public void recordCommand(OrderResponse observed,OrderResponse result,ru.itmo.highload.catering.kitchen.domain.model.KitchenAction action) {
        observe(observed);
        var type=switch(action) {
            case START_COOKING -> ru.itmo.highload.common.domain.events.EventType.COOKING_STARTED;
            case MARK_READY -> ru.itmo.highload.common.domain.events.EventType.ORDER_READY;
            case COMPLETE -> null;
        };
        if(type==null) return;
        var id=UUID.nameUUIDFromBytes((type+":"+result.id()+":"+result.version()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        // Retry caller is not the original audit actor; authoritative history stays in Order.
        outbox.append(new ru.itmo.highload.common.domain.events.EventEnvelope(id,type,result.id(),java.time.Instant.now(),1,
                ru.itmo.highload.common.domain.events.EventEnvelope.Payload.order(result.organizationId(),result.version(),null)));
    }
    public void consume(ru.itmo.highload.common.domain.events.EventEnvelope event) {
        var type=event.eventType();
        if(type!=ru.itmo.highload.common.domain.events.EventType.ORDER_CONFIRMED
                && type!=ru.itmo.highload.common.domain.events.EventType.ORDER_CANCELLED) return;
        if(!inbox.claim(event.eventId())) return;
        var status=type==ru.itmo.highload.common.domain.events.EventType.ORDER_CONFIRMED
                ? ru.itmo.highload.catering.kitchen.domain.model.OrderStatus.CONFIRMED
                : ru.itmo.highload.catering.kitchen.domain.model.OrderStatus.CANCELLED;
        if(status==ru.itmo.highload.catering.kitchen.domain.model.OrderStatus.CONFIRMED || tasks.existsById(event.aggregateId()))
            tasks.observe(new KitchenObservation(event.aggregateId(),status,event.payload().orderVersion()));
    }
    public List<UUID> oldestActiveIds() { return tasks.oldestActiveIds(50); }
    public void observeStates(List<OrderState> states) {
        states.forEach(state -> tasks.observe(new KitchenObservation(state.id(),state.status(),state.version())));
    }
}
