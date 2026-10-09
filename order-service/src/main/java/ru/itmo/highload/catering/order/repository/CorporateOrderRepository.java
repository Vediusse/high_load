package ru.itmo.highload.catering.order.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.itmo.highload.catering.order.entity.CorporateOrder;
import ru.itmo.highload.catering.order.entity.OrderStatus;

public interface CorporateOrderRepository extends JpaRepository<CorporateOrder, UUID> {

    Page<CorporateOrder> findByStatusIn(java.util.Collection<OrderStatus> statuses, Pageable pageable);

    @EntityGraph(attributePaths = "lines")
    Optional<CorporateOrder> findDetailedById(UUID id);

    @Query("""
            select o
            from CorporateOrder o
            where (:status is null or o.status = :status)
              and (:organizationId is null or o.organizationId = :organizationId)
              and (:excludeDraft = false or o.status <> 'DRAFT')
            """)
    Page<CorporateOrder> findPage(
            @Param("status") OrderStatus status,
            @Param("organizationId") UUID organizationId,
            @Param("excludeDraft") boolean excludeDraft,
            Pageable pageable);

    @Query("""
            select o from CorporateOrder o
            where o.status in :statuses
              and (:organizationId is null or o.organizationId = :organizationId)
            """)
    Page<CorporateOrder> findKitchenPage(
            @Param("statuses") java.util.Collection<OrderStatus> statuses,
            @Param("organizationId") UUID organizationId,
            Pageable pageable);
}
