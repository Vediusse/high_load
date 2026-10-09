package ru.itmo.highload.catering.identity.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.itmo.highload.catering.identity.entity.AppUser;

public interface UserRepository extends JpaRepository<AppUser, UUID> {
    Optional<AppUser> findByLogin(String login);

    Slice<AppUser> findBy(Pageable page);
}
