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
    @org.springframework.data.jpa.repository.Query("select u.id from AppUser u join u.roles r where u.status=ru.itmo.highload.catering.identity.domain.model.UserStatus.ACTIVE and r.code=:role and (:organizationId is null or u.organizationId=:organizationId) order by u.id")
    Slice<UUID> recipients(ru.itmo.highload.catering.identity.domain.model.RoleCode role, UUID organizationId, Pageable page);
}
