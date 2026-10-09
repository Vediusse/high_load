package ru.itmo.highload.common.error;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;

public final class ErrorResponses {
    private ErrorResponses() { }

    public static ApiError response(HttpStatus status, String code, String message,
                                                    List<FieldErrorResponse> fields, ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(status);
        return new ApiError(code, message, fields, exchange.getAttribute("traceId"));
    }
}
