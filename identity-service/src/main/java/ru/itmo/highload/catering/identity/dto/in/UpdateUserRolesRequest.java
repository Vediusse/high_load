package ru.itmo.highload.catering.identity.dto.in;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import ru.itmo.highload.catering.identity.entity.RoleCode;

public record UpdateUserRolesRequest(@NotEmpty @Size(max = 4) List<@NotNull RoleCode> roles) {}
