package ru.itmo.highload.catering.order.application.ports;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import ru.itmo.highload.catering.order.domain.model.CorporateOrder;
import ru.itmo.highload.catering.order.domain.model.OrderStatus;
import ru.itmo.highload.common.domain.PageQuery;
import ru.itmo.highload.common.domain.PageResult;

public interface OrderStore {
    CorporateOrder saveAndFlush(CorporateOrder order);

    Optional<CorporateOrder> findDetailedById(UUID id);

    List<CorporateOrder> findAllById(Set<UUID> ids);

    PageResult<CorporateOrder> findPage(
            OrderStatus status, UUID organizationId, boolean excludeDraft, PageQuery page);

    PageResult<CorporateOrder> findKitchenPage(
            Collection<OrderStatus> statuses, UUID organizationId, PageQuery page);

    void delete(CorporateOrder order);

    void flush();
}
