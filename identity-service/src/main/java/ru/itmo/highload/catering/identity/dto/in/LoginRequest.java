package ru.itmo.highload.catering.identity.dto.in;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank @Size(max = 256) String login, @NotBlank @Size(max = 72) String password) {
    @Override
    public String toString() {
        return "LoginRequest[redacted]";
    }
}
