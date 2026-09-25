package ru.itmo.highload.catering.common.dto;

import java.util.List;

public record PageResponse<T>(List<T> items, int page, int size, boolean hasNext) {
}
