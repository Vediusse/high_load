package ru.itmo.highload.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import ru.itmo.highload.common.error.RequestValidationException;

class PaginationTest {

    @Test
    void acceptsBoundaryValues() {
        assertThat(Pagination.pageRequest(0, 1).getPageSize()).isEqualTo(1);
        assertThat(Pagination.pageRequest(2, 50).getPageNumber()).isEqualTo(2);
        assertThat(Pagination.requireLimit(50)).isEqualTo(50);
    }

    @Test
    void rejectsInvalidPageSizeAndLimit() {
        assertThatThrownBy(() -> Pagination.pageRequest(-1, 51))
                .isInstanceOf(RequestValidationException.class)
                .satisfies(exception -> assertThat(((RequestValidationException) exception).getFieldErrors())
                        .extracting("field")
                        .containsExactly("page", "size"));
        assertThatThrownBy(() -> Pagination.requireLimit(0))
                .isInstanceOf(RequestValidationException.class);
    }
}
