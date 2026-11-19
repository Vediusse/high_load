package ru.itmo.highload.catering.organization.adapters.out.persistence;

import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.itmo.highload.catering.organization.domain.model.DeliveryPoint;

public interface DeliveryPointRepository extends JpaRepository<DeliveryPoint, UUID> {

    Slice<DeliveryPoint> findAllByOrganizationId(UUID organizationId, Pageable pageable);

    boolean existsByOrganizationIdAndName(UUID organizationId, String name);

    boolean existsByOrganizationIdAndNameAndIdNot(UUID organizationId, String name, UUID id);
}
