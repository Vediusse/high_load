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

    @EntityGraph(attributePaths = "lines")
    @Query("select o from CorporateOrder o where o.id = :id")
    Optional<CorporateOrder> findDetailedById(@Param("id") UUID id);

    @Query("""
            select o
            from CorporateOrder o
            where (:status is null or o.status = :status)
              and (:organizationId is null or o.organizationId = :organizationId)
            """)
    Page<CorporateOrder> findPage(
            @Param("status") OrderStatus status,
            @Param("organizationId") UUID organizationId,
            Pageable pageable);
}
