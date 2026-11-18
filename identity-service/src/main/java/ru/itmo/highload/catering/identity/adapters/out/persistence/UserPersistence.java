package ru.itmo.highload.catering.identity.adapters.out.persistence;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import ru.itmo.highload.common.domain.*;
import ru.itmo.highload.catering.identity.application.ports.UserStore;
import ru.itmo.highload.catering.identity.domain.model.AppUser;
@Repository @RequiredArgsConstructor
public class UserPersistence implements UserStore {
    private final UserRepository users;
    public Optional<AppUser> findByLogin(String login) { return users.findByLogin(login); }
    public Optional<AppUser> findById(UUID id) { return users.findById(id); }
    public AppUser saveAndFlush(AppUser user) { return users.saveAndFlush(user); }
    public void flush() { users.flush(); }
    public long count() { return users.count(); }
    public PageResult<AppUser> findBy(PageQuery page) {
        var result = users.findBy(PageRequest.of(page.page(), page.size(), Sort.by("id")));
        return new PageResult<>(result.getContent(), page.page(), page.size(), -1, result.hasNext());
    }
}
