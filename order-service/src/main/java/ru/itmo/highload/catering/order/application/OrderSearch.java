package ru.itmo.highload.catering.order.application;

import java.util.UUID;
import ru.itmo.highload.catering.order.domain.model.OrderStatus;
import ru.itmo.highload.common.domain.PageQuery;

public record OrderSearch(PageQuery page, OrderStatus status, UUID organizationId) {}
