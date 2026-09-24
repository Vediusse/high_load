package ru.itmo.highload.catering.organization.dto;

import ru.itmo.highload.catering.common.dto.PageResponse;

public record OrganizationPageResult(PageResponse<OrganizationResponse> body, long totalCount) {
}
