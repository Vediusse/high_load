package ru.itmo.highload.catering.organization.adapters.out.persistence;

import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import ru.itmo.highload.catering.organization.application.ports.DeliveryPointStore;
import ru.itmo.highload.catering.organization.domain.model.DeliveryPoint;
import ru.itmo.highload.catering.order.adapters.out.persistence.Pages;
import ru.itmo.highload.common.domain.*;
@Repository @RequiredArgsConstructor
public class DeliveryPointPersistence implements DeliveryPointStore {
    private final DeliveryPointRepository repository;
    public DeliveryPoint saveAndFlush(DeliveryPoint value) { return repository.saveAndFlush(value); }
    public Optional<DeliveryPoint> findById(UUID id) { return repository.findById(id); }
    public PageResult<DeliveryPoint> findAllByOrganizationId(UUID id,PageQuery page) { return Pages.result(repository.findAllByOrganizationId(id,Pages.request(page))); }
    public boolean existsByOrganizationIdAndName(UUID id,String name) { return repository.existsByOrganizationIdAndName(id,name); }
    public boolean existsByOrganizationIdAndNameAndIdNot(UUID org,String name,UUID id) { return repository.existsByOrganizationIdAndNameAndIdNot(org,name,id); }
    public void flush() { repository.flush(); }
}
