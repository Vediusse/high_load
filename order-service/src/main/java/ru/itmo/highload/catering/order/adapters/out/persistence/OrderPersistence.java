package ru.itmo.highload.catering.order.adapters.out.persistence;

import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import ru.itmo.highload.catering.order.application.ports.OrderStore;
import ru.itmo.highload.catering.order.domain.model.*;
import ru.itmo.highload.common.domain.*;
@Repository @RequiredArgsConstructor
public class OrderPersistence implements OrderStore {
    private final CorporateOrderRepository repository;
    public CorporateOrder saveAndFlush(CorporateOrder order) { return repository.saveAndFlush(order); }
    public Optional<CorporateOrder> findDetailedById(UUID id) { return repository.findDetailedById(id); }
    public List<CorporateOrder> findAllById(Set<UUID> ids) { return repository.findAllById(ids); }
    public PageResult<CorporateOrder> findPage(OrderStatus status,UUID organizationId,boolean excludeDraft,PageQuery page) { return Pages.result(repository.findPage(status,organizationId,excludeDraft,Pages.request(page))); }
    public PageResult<CorporateOrder> findKitchenPage(Collection<OrderStatus> statuses,UUID organizationId,PageQuery page) { return Pages.result(repository.findKitchenPage(statuses,organizationId,Pages.request(page))); }
    public void delete(CorporateOrder order) { repository.delete(order); }
    public void flush() {
        try { repository.flush(); } catch(ObjectOptimisticLockingFailureException error) { throw new ConcurrentWrite(error); }
    }
}
