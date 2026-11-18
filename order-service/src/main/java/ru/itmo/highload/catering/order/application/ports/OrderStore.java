package ru.itmo.highload.catering.order.application.ports;

import java.util.*;
import ru.itmo.highload.catering.order.domain.model.*;
import ru.itmo.highload.common.domain.*;
public interface OrderStore {
    CorporateOrder saveAndFlush(CorporateOrder order);
    Optional<CorporateOrder> findDetailedById(UUID id);
    List<CorporateOrder> findAllById(Set<UUID> ids);
    PageResult<CorporateOrder> findPage(OrderStatus status, UUID organizationId, boolean excludeDraft, PageQuery page);
    PageResult<CorporateOrder> findKitchenPage(Collection<OrderStatus> statuses, UUID organizationId, PageQuery page);
    void delete(CorporateOrder order);
    void flush();
}
