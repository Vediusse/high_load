package ru.itmo.highload.catering.kitchen.service;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.highload.catering.kitchen.client.dto.OrderResponse;
import ru.itmo.highload.catering.kitchen.client.dto.OrderState;
import ru.itmo.highload.catering.kitchen.client.dto.OrderStatus;
import ru.itmo.highload.catering.kitchen.repository.KitchenTaskRepository;
import ru.itmo.highload.common.web.Pagination;

@Service
@RequiredArgsConstructor
public class TaskProjection {
    private final KitchenTaskRepository tasks;

    @Transactional
    public void observe(OrderResponse order) {
        if (Set.of(OrderStatus.CONFIRMED, OrderStatus.IN_COOKING, OrderStatus.READY, OrderStatus.COMPLETED)
                .contains(order.status()) || tasks.existsById(order.id())) {
            tasks.observe(order.id(), order.status().name(), order.version());
        }
    }

    @Transactional(readOnly = true)
    public List<UUID> oldestActiveIds() { return tasks.oldestActiveIds(Pagination.MAX_SIZE); }

    @Transactional
    public void observeStates(List<OrderState> states) {
        states.forEach(state -> tasks.observe(state.id(), state.status().name(), state.version()));
    }
}
