package ru.itmo.highload.common.web;

import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import ru.itmo.highload.common.domain.PageQuery;
import ru.itmo.highload.common.dto.out.FieldErrorResponse;
import ru.itmo.highload.common.error.RequestValidationException;

public final class Pagination {
    public static PageQuery query(int page, int size) {
        validate(page, size);
        return PageQuery.of(page, size);
    }

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 50;

    private Pagination() {}

    public static PageRequest pageRequest(int page, int size) {
        validate(page, size);
        return PageRequest.of(page, size);
    }

    private static void validate(int page, int size) {
        List<FieldErrorResponse> errors = new ArrayList<>();
        if (page < 0) {
            errors.add(new FieldErrorResponse("page", "должно быть не меньше 0"));
        }
        if (size < 1 || size > MAX_SIZE) {
            errors.add(new FieldErrorResponse("size", "должно быть от 1 до 50"));
        }
        if (!errors.isEmpty()) {
            throw new RequestValidationException(errors);
        }
    }

    public static int requireLimit(int limit) {
        if (limit < 1 || limit > MAX_SIZE) {
            throw new RequestValidationException(
                    List.of(new FieldErrorResponse("limit", "должно быть от 1 до 50")));
        }
        return limit;
    }
}
