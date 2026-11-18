package ru.itmo.highload.catering.organization.application.ports;

import java.util.*;
import ru.itmo.highload.catering.organization.domain.model.Organization;
import ru.itmo.highload.common.domain.*;
public interface OrganizationStore {
    Organization saveAndFlush(Organization organization);
    Optional<Organization> findById(UUID id);
    PageResult<Organization> findById(UUID id, PageQuery page);
    PageResult<Organization> findAll(PageQuery page);
    void flush();
}
