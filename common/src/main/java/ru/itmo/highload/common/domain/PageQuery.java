package ru.itmo.highload.common.domain;

public record PageQuery(int page, int size, String sort) {
    public PageQuery {
        if (page < 0 || size < 1 || size > 50) throw new IllegalArgumentException("Invalid page bounds");
    }
    public static PageQuery of(int page, int size) { return new PageQuery(page, size, "id ASC"); }
    public static PageQuery of(int page, int size, String sort) { return new PageQuery(page, size, sort); }
    public PageQuery withSort(String sort) { return new PageQuery(page, size, sort); }
    public int getPageNumber() { return page; }
    public int getPageSize() { return size; }
    public long getOffset() { return (long) page * size; }
}
