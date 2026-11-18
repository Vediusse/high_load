package ru.itmo.highload.catering.identity.application.ports;

import java.util.Optional;
import java.util.UUID;
import ru.itmo.highload.catering.identity.domain.model.AppUser;
import ru.itmo.highload.catering.identity.domain.model.RoleCode;
import ru.itmo.highload.common.domain.PageQuery;
import ru.itmo.highload.common.domain.PageResult;

public interface UserStore {
    Optional<AppUser> findByLogin(String login);

    Optional<AppUser> findById(UUID id);

    AppUser saveAndFlush(AppUser user);

    void flush();

    long count();

    PageResult<AppUser> findBy(PageQuery page);

    PageResult<UUID> recipients(RoleCode role, UUID organizationId, PageQuery page);
}
