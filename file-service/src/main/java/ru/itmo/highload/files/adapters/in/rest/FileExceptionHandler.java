package ru.itmo.highload.files.adapters.in.rest;
import java.util.List;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import ru.itmo.highload.common.dto.out.ApiError;
import ru.itmo.highload.common.error.ErrorResponses;
import ru.itmo.highload.files.domain.FileFailure;
@RestControllerAdvice @Order(-25)
public class FileExceptionHandler {
    @ExceptionHandler(FileFailure.class)
    public ApiError file(FileFailure error,ServerWebExchange exchange) {
        var status=switch(error.kind()) { case TOO_LARGE->HttpStatus.PAYLOAD_TOO_LARGE;case UNSUPPORTED_TYPE->HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            case CONTENT_MISMATCH->HttpStatus.UNPROCESSABLE_ENTITY;case INTEGRITY->HttpStatus.INTERNAL_SERVER_ERROR;case STORAGE->HttpStatus.SERVICE_UNAVAILABLE; };
        return ErrorResponses.response(status,error.code(),error.getMessage(),List.of(),exchange);
    }
    @ExceptionHandler(DataBufferLimitException.class)
    public ApiError size(DataBufferLimitException error,ServerWebExchange exchange) {
        return ErrorResponses.response(HttpStatus.PAYLOAD_TOO_LARGE,"FILE_TOO_LARGE","Превышен допустимый размер файла",List.of(),exchange);
    }
}
