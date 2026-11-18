package ru.itmo.highload.catering.kitchen.adapters.out.persistence;

import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import ru.itmo.highload.catering.kitchen.application.ports.KitchenTaskStore;
import ru.itmo.highload.catering.kitchen.domain.model.KitchenObservation;
@Repository @RequiredArgsConstructor
public class KitchenTaskPersistence implements KitchenTaskStore {
    private final KitchenTaskRepository repository;
    public boolean existsById(UUID id) { return repository.existsById(id); }
    public void observe(KitchenObservation observation) {
        repository.observe(observation.orderId(),observation.status().name(),observation.version());
    }
    public List<UUID> oldestActiveIds(int limit) { return repository.oldestActiveIds(limit); }
}
