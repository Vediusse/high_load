package ru.itmo.highload.catering.order.adapters.out.persistence;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;
import ru.itmo.highload.catering.order.domain.model.OrderStatusHistory;

public interface OrderStatusHistoryRepository extends Repository<OrderStatusHistory, UUID> {

    Page<OrderStatusHistory> findByOrder_Id(UUID orderId, Pageable pageable);
}
