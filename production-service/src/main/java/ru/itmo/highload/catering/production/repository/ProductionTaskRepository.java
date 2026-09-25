package ru.itmo.highload.catering.production.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import ru.itmo.highload.catering.production.entity.ProductionTask;

public interface ProductionTaskRepository extends JpaRepository<ProductionTask, UUID> {
    @Modifying
    @Query(value = """
            insert into production_task (order_id, status, order_version, updated_at, version)
            values (:id, :status, :orderVersion, now(), 0)
            on conflict (order_id) do update set status = excluded.status,
                order_version = excluded.order_version, updated_at = now(), version = production_task.version + 1
            where production_task.order_version < excluded.order_version
            """, nativeQuery = true)
    void observe(@Param("id") UUID id, @Param("status") String status, @Param("orderVersion") long version);

    @Query(value = """
            select order_id from production_task
            where status in ('CONFIRMED', 'IN_COOKING', 'READY')
                and (cast(:afterId as uuid) is null or order_id > :afterId)
            order by order_id limit 50
            """, nativeQuery = true)
    List<UUID> activeIdsAfter(@Param("afterId") UUID afterId);
}
