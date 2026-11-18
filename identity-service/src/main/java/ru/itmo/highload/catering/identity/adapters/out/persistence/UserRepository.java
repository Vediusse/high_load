package ru.itmo.highload.catering.identity.adapters.out.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.itmo.highload.catering.identity.domain.model.AppUser;

public interface UserRepository extends JpaRepository<AppUser, UUID> {
    Optional<AppUser> findByLogin(String login);

    Slice<AppUser> findBy(Pageable page);

    @org.springframework.data.jpa.repository.Query(
            """
            select account.id
            from AppUser account join account.roles assignedRole
            where account.status = ru.itmo.highload.catering.identity.domain.model.UserStatus.ACTIVE
              and assignedRole.code = :role
              and (
                  :organizationId is null
                  or account.organizationId = :organizationId
                  or not exists (
                      select organizationRole.id from account.roles organizationRole
                      where organizationRole.code = ru.itmo.highload.catering.identity.domain.model.RoleCode.ORGANIZATION_REPRESENTATIVE
                  )
              )
            order by account.id
            """)
    Slice<UUID> recipients(
            ru.itmo.highload.catering.identity.domain.model.RoleCode role,
            UUID organizationId,
            Pageable page);
}
