package ru.itmo.highload.catering.identity.application.ports;
import java.util.*;
import ru.itmo.highload.catering.identity.domain.model.*;
import ru.itmo.highload.catering.identity.application.dto.out.TokenResponse;
import ru.itmo.highload.common.domain.*;
public interface UserStore { Optional<AppUser> findByLogin(String login); Optional<AppUser> findById(UUID id);
    AppUser saveAndFlush(AppUser user); void flush(); long count();
    PageResult<AppUser> findBy(PageQuery page);
}
