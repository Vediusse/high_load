package ru.itmo.highload.catering.kitchen.application;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import ru.itmo.highload.catering.kitchen.application.dto.out.OrderResponse;
import ru.itmo.highload.catering.kitchen.application.dto.out.OrderState;
import ru.itmo.highload.catering.kitchen.application.ports.KitchenTaskStore;
import ru.itmo.highload.catering.kitchen.domain.model.KitchenAction;
import ru.itmo.highload.catering.kitchen.domain.model.KitchenObservation;
import ru.itmo.highload.catering.kitchen.domain.model.OrderStatus;
import ru.itmo.highload.common.domain.events.EventEnvelope;
import ru.itmo.highload.common.domain.events.EventInbox;
import ru.itmo.highload.common.domain.events.EventOutbox;
import ru.itmo.highload.common.domain.events.EventType;

@RequiredArgsConstructor
public class TaskProjection {
    private final KitchenTaskStore tasks;
    private final EventOutbox outbox;
    private final EventInbox inbox;

    public void observe(OrderResponse order) {
        var observation = new KitchenObservation(order.id(), order.status(), order.version());
        if (observation.isCookingState() || tasks.existsById(order.id()))
            tasks.observe(observation);
    }

    public void recordCommand(OrderResponse observed, OrderResponse result, KitchenAction action) {
        observe(observed);
        var type =
                switch (action) {
                    case START_COOKING -> EventType.COOKING_STARTED;
                    case MARK_READY -> EventType.ORDER_READY;
                    case COMPLETE -> null;
                };
        if (type == null) return;
        var id =
                UUID.nameUUIDFromBytes(
                        (type + ":" + result.id() + ":" + result.version())
                                .getBytes(StandardCharsets.UTF_8));
        // Retry caller is not the original audit actor; authoritative history stays in Order.
        outbox.append(
                new EventEnvelope(
                        id,
                        type,
                        result.id(),
                        Instant.now(),
                        1,
                        EventEnvelope.Payload.order(
                                result.organizationId(), result.version(), null)));
    }

    public void consume(EventEnvelope event) {
        var type = event.eventType();
        if (type != EventType.ORDER_CONFIRMED && type != EventType.ORDER_CANCELLED) return;
        if (!inbox.claim(event.eventId())) return;
        var status =
                type == EventType.ORDER_CONFIRMED ? OrderStatus.CONFIRMED : OrderStatus.CANCELLED;
        if (status == OrderStatus.CONFIRMED || tasks.existsById(event.aggregateId()))
            tasks.observe(
                    new KitchenObservation(
                            event.aggregateId(), status, event.payload().orderVersion()));
    }

    public List<UUID> oldestActiveIds() {
        return tasks.oldestActiveIds(50);
    }

    public void observeStates(List<OrderState> states) {
        states.forEach(state ->
                tasks.observe(new KitchenObservation(state.id(), state.status(), state.version())));
    }
}
