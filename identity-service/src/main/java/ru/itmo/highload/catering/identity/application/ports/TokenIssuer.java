package ru.itmo.highload.catering.identity.application.ports;

import ru.itmo.highload.catering.identity.application.dto.out.TokenResponse;
import ru.itmo.highload.catering.identity.domain.model.AppUser;

public interface TokenIssuer {
    TokenResponse issue(AppUser user);
}
