package ru.itmo.highload.catering.kitchen.adapters.out.persistence;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import ru.itmo.highload.catering.kitchen.application.ports.KitchenTaskStore;
import ru.itmo.highload.catering.kitchen.domain.model.KitchenObservation;
import ru.itmo.highload.catering.kitchen.domain.model.KitchenTask;
import ru.itmo.highload.catering.kitchen.domain.model.OrderStatus;

@Repository
@RequiredArgsConstructor
public class KitchenTaskPersistence implements KitchenTaskStore {
    private final KitchenTaskRepository repository;

    public boolean existsById(UUID id) {
        return repository.existsById(id);
    }

    public void observe(KitchenObservation observation) {
        repository.observe(
                observation.orderId(), observation.status().name(), observation.version());
    }

    public List<UUID> oldestActiveIds(int limit) {
        return repository
                .findByStatusInOrderByUpdatedAtAscOrderIdAsc(
                        Set.of(OrderStatus.CONFIRMED, OrderStatus.IN_COOKING, OrderStatus.READY),
                        PageRequest.of(0, limit))
                .stream()
                .map(KitchenTask::getOrderId)
                .toList();
    }
}
