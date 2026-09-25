package ru.itmo.highload.catering.common.error;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> handleApiException(ApiException exception, HttpServletRequest request) {
        return response(
                exception.getStatus(),
                exception.getCode(),
                exception.getMessage(),
                exception.getFieldErrors(),
                request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        List<FieldErrorResponse> fieldErrors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldErrorResponse(error.getField(), error.getDefaultMessage()))
                .sorted(Comparator.comparing(FieldErrorResponse::field))
                .toList();
        return response(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "Запрос содержит некорректные поля",
                fieldErrors,
                request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> handleMalformedJson(
            HttpMessageNotReadableException exception,
            HttpServletRequest request) {
        return response(
                HttpStatus.BAD_REQUEST,
                "MALFORMED_JSON",
                "Тело запроса содержит некорректный JSON",
                List.of(),
                request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> handleTypeMismatch(
            MethodArgumentTypeMismatchException exception,
            HttpServletRequest request) {
        return response(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "Параметр запроса имеет неверный формат",
                List.of(new FieldErrorResponse(exception.getName(), "имеет неверный формат")),
                request);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<ApiError> handleOptimisticLock(
            ObjectOptimisticLockingFailureException exception,
            HttpServletRequest request) {
        return response(
                HttpStatus.CONFLICT,
                "RESOURCE_VERSION_CONFLICT",
                "Объект был изменён параллельно; перечитайте актуальное состояние",
                List.of(),
                request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> handleDataIntegrity(
            DataIntegrityViolationException exception,
            HttpServletRequest request) {
        return response(
                HttpStatus.CONFLICT,
                "DATA_INTEGRITY_CONFLICT",
                "Изменение конфликтует с текущими данными",
                List.of(),
                request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception exception, HttpServletRequest request) {
        String traceId = traceId(request);
        log.error("Unexpected request failure, traceId={}", traceId, exception);
        ApiError error = new ApiError(
                "INTERNAL_ERROR",
                "Внутренняя ошибка сервера",
                List.of(),
                traceId);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

    private ResponseEntity<ApiError> response(
            HttpStatus status,
            String code,
            String message,
            List<FieldErrorResponse> fieldErrors,
            HttpServletRequest request) {
        return ResponseEntity.status(status)
                .body(new ApiError(code, message, fieldErrors, traceId(request)));
    }

    private String traceId(HttpServletRequest request) {
        Object traceId = request.getAttribute(RequestTraceFilter.TRACE_ID_ATTRIBUTE);
        return traceId instanceof String value ? value : UUID.randomUUID().toString();
    }
}
