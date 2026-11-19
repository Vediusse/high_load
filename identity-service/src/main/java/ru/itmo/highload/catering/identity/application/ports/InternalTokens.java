package ru.itmo.highload.catering.identity.application.ports;

import java.time.Instant;
import ru.itmo.highload.catering.identity.domain.IdentityActor;
import ru.itmo.highload.common.dto.out.InternalTokenResponse;

public interface InternalTokens {
    InternalTokenResponse issue(IdentityActor actor, Instant externalExpiresAt);
}
