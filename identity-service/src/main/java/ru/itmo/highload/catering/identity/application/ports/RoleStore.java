package ru.itmo.highload.catering.identity.application.ports;

import java.util.Set;
import ru.itmo.highload.catering.identity.domain.model.Role;
import ru.itmo.highload.catering.identity.domain.model.RoleCode;

public interface RoleStore {
    Set<Role> findByCodeIn(Set<RoleCode> codes);
}
