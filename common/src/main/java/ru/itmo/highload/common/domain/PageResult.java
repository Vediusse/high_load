package ru.itmo.highload.common.domain;

import java.util.List;

public record PageResult<T>(List<T> items, int page, int size, long total, boolean hasNext) {
    public PageResult {
        items = List.copyOf(items);
    }
}
