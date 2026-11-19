package ru.itmo.highload.common.error;

import java.util.List;
import lombok.Getter;
import org.springframework.http.HttpStatus;
import ru.itmo.highload.common.dto.out.FieldErrorResponse;

@Getter
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final List<FieldErrorResponse> fieldErrors;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, List.of());
    }

    public ApiException(
            HttpStatus status, String code, String message, List<FieldErrorResponse> fieldErrors) {
        super(message);
        this.status = status;
        this.code = code;
        this.fieldErrors = List.copyOf(fieldErrors);
    }
}
