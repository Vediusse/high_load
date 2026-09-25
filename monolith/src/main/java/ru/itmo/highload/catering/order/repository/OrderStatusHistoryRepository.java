package ru.itmo.highload.catering.order.repository;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;
import ru.itmo.highload.catering.order.entity.OrderStatusHistory;

public interface OrderStatusHistoryRepository extends Repository<OrderStatusHistory, UUID> {

    Page<OrderStatusHistory> findByOrder_Id(UUID orderId, Pageable pageable);
}
