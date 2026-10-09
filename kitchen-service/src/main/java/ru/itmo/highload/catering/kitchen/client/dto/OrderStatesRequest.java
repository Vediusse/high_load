package ru.itmo.highload.catering.kitchen.client.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Set;
import java.util.UUID;
import ru.itmo.highload.common.web.Pagination;

public record OrderStatesRequest(@NotNull @Size(min = 1, max = Pagination.MAX_SIZE) Set<@NotNull UUID> ids) { }
