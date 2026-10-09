package ru.itmo.highload.catering.identity.dto.in;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import ru.itmo.highload.catering.identity.entity.RoleCode;

public record CreateUserRequest(
        @NotBlank @Size(max = 256) String login,
        @NotBlank @Size(max = 72) String password,
        @NotEmpty @Size(max = 4) List<@NotNull RoleCode> roles,
        UUID organizationId) {
    @Override
    public String toString() {
        return "CreateUserRequest[redacted]";
    }
}
