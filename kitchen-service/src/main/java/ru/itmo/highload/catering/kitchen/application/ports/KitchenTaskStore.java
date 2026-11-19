package ru.itmo.highload.catering.kitchen.application.ports;

import java.util.List;
import java.util.UUID;
import ru.itmo.highload.catering.kitchen.domain.model.KitchenObservation;

public interface KitchenTaskStore {
    boolean existsById(UUID id);

    void observe(KitchenObservation observation);

    List<UUID> oldestActiveIds(int limit);
}
