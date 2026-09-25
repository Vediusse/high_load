package ru.itmo.highload.catering.organization.repository;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.itmo.highload.catering.organization.entity.Organization;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> {
}
