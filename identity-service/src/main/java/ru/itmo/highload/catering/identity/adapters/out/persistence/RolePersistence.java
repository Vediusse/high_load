package ru.itmo.highload.catering.identity.adapters.out.persistence;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import ru.itmo.highload.catering.identity.application.ports.RoleStore;
import ru.itmo.highload.catering.identity.domain.model.*;
@Repository @RequiredArgsConstructor
public class RolePersistence implements RoleStore {
    private final RoleRepository roles;
    public Set<Role> findByCodeIn(Set<RoleCode> codes) { return roles.findByCodeIn(codes); }
}
