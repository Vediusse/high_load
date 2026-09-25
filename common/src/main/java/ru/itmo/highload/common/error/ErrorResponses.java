package ru.itmo.highload.common.error;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ServerWebExchange;

public final class ErrorResponses {
    private ErrorResponses() { }

    public static ResponseEntity<ApiError> response(HttpStatus status, String code, String message,
                                                    List<FieldErrorResponse> fields, ServerWebExchange exchange) {
        return ResponseEntity.status(status).body(new ApiError(code, message, fields, exchange.getAttribute("traceId")));
    }
}
