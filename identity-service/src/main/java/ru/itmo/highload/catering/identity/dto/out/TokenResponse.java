package ru.itmo.highload.catering.identity.dto.out;

import java.time.Instant;

public record TokenResponse(String accessToken, String tokenType, Instant expiresAt) {
    @Override
    public String toString() {
        return "TokenResponse[redacted]";
    }
}
