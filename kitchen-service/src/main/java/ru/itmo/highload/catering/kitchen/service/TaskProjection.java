package ru.itmo.highload.catering.kitchen.service;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.highload.catering.order.dto.OrderResponse;
import ru.itmo.highload.catering.order.entity.OrderStatus;
import ru.itmo.highload.catering.kitchen.repository.KitchenTaskRepository;

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
    public List<UUID> activeIdsAfter(UUID afterId) { return tasks.activeIdsAfter(afterId); }
}
