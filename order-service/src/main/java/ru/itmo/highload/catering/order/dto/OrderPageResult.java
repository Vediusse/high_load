package ru.itmo.highload.catering.order.dto;

import ru.itmo.highload.common.dto.PageResponse;

public record OrderPageResult(PageResponse<OrderResponse> body, long totalCount) {
}
