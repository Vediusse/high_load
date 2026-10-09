package ru.itmo.highload.catering.order.dto.out;

import ru.itmo.highload.common.dto.out.PageResponse;

public record OrderPageResult(PageResponse<OrderResponse> body, long totalCount) {
}
