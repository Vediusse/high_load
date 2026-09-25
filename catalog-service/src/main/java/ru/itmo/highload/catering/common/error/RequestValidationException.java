package ru.itmo.highload.catering.common.error;

import java.util.List;
import org.springframework.http.HttpStatus;

public class RequestValidationException extends ApiException {

    public RequestValidationException(List<FieldErrorResponse> fieldErrors) {
        super(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "Запрос содержит некорректные параметры",
                fieldErrors);
    }
}
