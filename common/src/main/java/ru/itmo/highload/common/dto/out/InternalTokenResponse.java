package ru.itmo.highload.common.dto.out;

import java.time.Instant;

public record InternalTokenResponse(String accessToken, String tokenType, Instant expiresAt) {
    @Override public String toString() { return "InternalTokenResponse[redacted]"; }
}
