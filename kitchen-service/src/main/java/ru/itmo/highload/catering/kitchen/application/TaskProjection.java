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
    public void observe(OrderResponse order) {
        var observation=new KitchenObservation(order.id(),order.status(),order.version());
        if(observation.isCookingState() || tasks.existsById(order.id())) tasks.observe(observation);
    }
    public List<UUID> oldestActiveIds() { return tasks.oldestActiveIds(50); }
    public void observeStates(List<OrderState> states) {
        states.forEach(state -> tasks.observe(new KitchenObservation(state.id(),state.status(),state.version())));
    }
}
