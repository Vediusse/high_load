package ru.itmo.highload.catering.organization.application.ports;

import java.util.*;
import ru.itmo.highload.catering.organization.domain.model.DeliveryPoint;
import ru.itmo.highload.common.domain.*;
public interface DeliveryPointStore {
    DeliveryPoint saveAndFlush(DeliveryPoint point);
    Optional<DeliveryPoint> findById(UUID id);
    PageResult<DeliveryPoint> findAllByOrganizationId(UUID id, PageQuery page);
    boolean existsByOrganizationIdAndName(UUID organizationId, String name);
    boolean existsByOrganizationIdAndNameAndIdNot(UUID organizationId, String name, UUID id);
    void flush();
}
