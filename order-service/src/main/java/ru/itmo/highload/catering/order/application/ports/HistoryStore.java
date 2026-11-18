package ru.itmo.highload.catering.order.application.ports;

import java.util.UUID;
import ru.itmo.highload.catering.order.domain.model.OrderStatusHistory;
import ru.itmo.highload.common.domain.PageQuery;
import ru.itmo.highload.common.domain.PageResult;

public interface HistoryStore {
    PageResult<OrderStatusHistory> findByOrder_Id(UUID id, PageQuery page);
}
