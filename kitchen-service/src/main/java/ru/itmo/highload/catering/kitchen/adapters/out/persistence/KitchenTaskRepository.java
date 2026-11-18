package ru.itmo.highload.catering.kitchen.adapters.out.persistence;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.itmo.highload.catering.kitchen.domain.model.KitchenTask;
import ru.itmo.highload.catering.kitchen.domain.model.OrderStatus;

public interface KitchenTaskRepository extends JpaRepository<KitchenTask, UUID> {
    @Modifying
    @Query(
            value =
                    """
                    insert into kitchen_task (order_id, status, order_version, updated_at, version)
                    values (:id, :status, :orderVersion, now(), 0)
                    on conflict (order_id) do update set status = case
                        when kitchen_task.order_version < excluded.order_version then excluded.status
                        else kitchen_task.status end,
                        order_version = excluded.order_version, updated_at = now(), version = kitchen_task.version + 1
                    where kitchen_task.order_version <= excluded.order_version
                    """,
            nativeQuery = true)
    void observe(
            @Param("id") UUID id,
            @Param("status") String status,
            @Param("orderVersion") long version);

    List<KitchenTask> findByStatusInOrderByUpdatedAtAscOrderIdAsc(
            Collection<OrderStatus> statuses, Pageable page);
}
