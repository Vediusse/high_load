package ru.itmo.highload.catering.identity.error;

import java.util.List;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebExchange;
import ru.itmo.highload.common.dto.out.ApiError;
import ru.itmo.highload.common.error.ErrorResponses;

@RestControllerAdvice
@Order(-20)
public class IdentityExceptionHandler {
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ApiError conflict(DataIntegrityViolationException error, ServerWebExchange exchange) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException constraint
                    && "uk_app_user_login".equals(constraint.getConstraintName())) {
                return ErrorResponses.response(
                        HttpStatus.CONFLICT,
                        "LOGIN_ALREADY_EXISTS",
                        "Логин уже используется",
                        List.of(),
                        exchange);
            }
        }
        return ErrorResponses.response(
                HttpStatus.CONFLICT,
                "DATA_INTEGRITY_CONFLICT",
                "Изменение конфликтует с текущими данными",
                List.of(),
                exchange);
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ApiError invalidToken(RuntimeException error, ServerWebExchange exchange) {
        exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        return ErrorResponses.response(
                HttpStatus.UNAUTHORIZED,
                "AUTHENTICATION_REQUIRED",
                "Требуется действительный токен",
                List.of(),
                exchange);
    }
}
