package ru.itmo.highload.common.error;

import java.util.List;
import org.springframework.http.HttpStatus;
import ru.itmo.highload.common.dto.out.FieldErrorResponse;

public class RequestValidationException extends ApiException {

    public RequestValidationException(List<FieldErrorResponse> fieldErrors) {
        super(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "Запрос содержит некорректные параметры",
                fieldErrors);
    }
}
