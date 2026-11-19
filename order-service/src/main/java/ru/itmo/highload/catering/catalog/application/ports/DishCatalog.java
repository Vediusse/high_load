package ru.itmo.highload.catering.catalog.application.ports;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import ru.itmo.highload.catering.catalog.application.dto.out.ActiveDishData;

public interface DishCatalog {
    Map<UUID, ActiveDishData> getActiveDishPrices(Set<UUID> ids, String bearer);
}
