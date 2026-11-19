package ru.itmo.highload.catering.organization.adapters.out.persistence;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.itmo.highload.catering.organization.domain.model.Organization;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> {
    Page<Organization> findById(UUID id, Pageable pageable);
}
