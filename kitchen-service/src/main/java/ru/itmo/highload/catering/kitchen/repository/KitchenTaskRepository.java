package ru.itmo.highload.catering.kitchen.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import ru.itmo.highload.catering.kitchen.entity.KitchenTask;

public interface KitchenTaskRepository extends JpaRepository<KitchenTask, UUID> {
    @Modifying
    @Query(value = """
            insert into kitchen_task (order_id, status, order_version, updated_at, version)
            values (:id, :status, :orderVersion, now(), 0)
            on conflict (order_id) do update set status = case
                when kitchen_task.order_version < excluded.order_version then excluded.status
                else kitchen_task.status end,
                order_version = excluded.order_version, updated_at = now(), version = kitchen_task.version + 1
            where kitchen_task.order_version <= excluded.order_version
            """, nativeQuery = true)
    void observe(@Param("id") UUID id, @Param("status") String status, @Param("orderVersion") long version);

    @Query(value = """
            select order_id from kitchen_task
            where status in ('CONFIRMED', 'IN_COOKING', 'READY')
            order by updated_at, order_id limit :limit
            """, nativeQuery = true)
    List<UUID> oldestActiveIds(@Param("limit") int limit);
}
