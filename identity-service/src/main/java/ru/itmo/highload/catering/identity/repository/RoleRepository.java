package ru.itmo.highload.catering.identity.repository;

import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.itmo.highload.catering.identity.entity.Role;
import ru.itmo.highload.catering.identity.entity.RoleCode;

public interface RoleRepository extends JpaRepository<Role, Short> {
    Set<Role> findByCodeIn(Set<RoleCode> codes);
}
