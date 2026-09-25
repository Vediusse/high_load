package ru.itmo.highload.catering.common.error;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Единый формат ошибки REST API")
public record ApiError(String code, String message, List<FieldErrorResponse> fieldErrors, String traceId) {
}
