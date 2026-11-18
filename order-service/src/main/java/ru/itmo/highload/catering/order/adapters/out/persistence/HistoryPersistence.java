package ru.itmo.highload.catering.order.adapters.out.persistence;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import ru.itmo.highload.catering.order.application.ports.HistoryStore;
import ru.itmo.highload.catering.order.domain.model.OrderStatusHistory;
import ru.itmo.highload.common.domain.PageQuery;
import ru.itmo.highload.common.domain.PageResult;

@Repository
@RequiredArgsConstructor
public class HistoryPersistence implements HistoryStore {
    private final OrderStatusHistoryRepository repository;

    public PageResult<OrderStatusHistory> findByOrder_Id(UUID id, PageQuery page) {
        return Pages.result(repository.findByOrder_Id(id, Pages.request(page)));
    }
}
