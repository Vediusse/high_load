package ru.itmo.highload.catering.organization.adapters.out.persistence;

import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import ru.itmo.highload.catering.order.adapters.out.persistence.Pages;
import ru.itmo.highload.catering.organization.application.ports.OrganizationStore;
import ru.itmo.highload.catering.organization.domain.model.Organization;
import ru.itmo.highload.common.domain.PageQuery;
import ru.itmo.highload.common.domain.PageResult;

@Repository
@RequiredArgsConstructor
public class OrganizationPersistence implements OrganizationStore {
    private final OrganizationRepository repository;

    public Organization saveAndFlush(Organization value) {
        return repository.saveAndFlush(value);
    }

    public Optional<Organization> findById(UUID id) {
        return repository.findById(id);
    }

    public PageResult<Organization> findById(UUID id, PageQuery page) {
        return Pages.result(repository.findById(id, Pages.request(page)));
    }

    public PageResult<Organization> findAll(PageQuery page) {
        return Pages.result(repository.findAll(Pages.request(page)));
    }

    public void flush() {
        repository.flush();
    }
}
