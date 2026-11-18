package ru.itmo.highload.catering.identity.adapters.out.persistence;

import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.itmo.highload.catering.identity.domain.model.Role;
import ru.itmo.highload.catering.identity.domain.model.RoleCode;

public interface RoleRepository extends JpaRepository<Role, Short> {
    Set<Role> findByCodeIn(Set<RoleCode> codes);
}
