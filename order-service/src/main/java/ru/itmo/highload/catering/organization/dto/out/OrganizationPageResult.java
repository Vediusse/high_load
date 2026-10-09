package ru.itmo.highload.catering.organization.dto.out;

import ru.itmo.highload.common.dto.out.PageResponse;

public record OrganizationPageResult(PageResponse<OrganizationResponse> body, long totalCount) {
}
