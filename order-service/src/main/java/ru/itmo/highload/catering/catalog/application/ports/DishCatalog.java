package ru.itmo.highload.catering.catalog.application.ports;

import java.util.*;
import ru.itmo.highload.catering.catalog.application.dto.out.ActiveDishData;
public interface DishCatalog {
    Map<UUID, ActiveDishData> getActiveDishPrices(Set<UUID> ids, String bearer);
}
