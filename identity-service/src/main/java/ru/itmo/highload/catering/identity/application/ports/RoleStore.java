package ru.itmo.highload.catering.identity.application.ports;
import java.util.*;
import ru.itmo.highload.catering.identity.domain.model.*;
import ru.itmo.highload.catering.identity.application.dto.out.TokenResponse;
import ru.itmo.highload.common.domain.*;
public interface RoleStore { Set<Role> findByCodeIn(Set<RoleCode> codes);
}
