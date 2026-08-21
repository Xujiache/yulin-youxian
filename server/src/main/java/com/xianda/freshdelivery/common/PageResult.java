package com.xianda.freshdelivery.common;

import java.util.List;

public record PageResult<T>(
        List<T> items,
        long total,
        int page,
        int pageSize
) {
    public static <T> PageResult<T> of(List<T> items) {
        return new PageResult<>(items, items.size(), 1, items.size());
    }

    public static <T> PageResult<T> page(List<T> items, Integer requestedPage, Integer requestedPageSize) {
        int pageSize = Math.max(1, Math.min(100, requestedPageSize == null ? 30 : requestedPageSize));
        int page = Math.max(1, requestedPage == null ? 1 : requestedPage);
        int total = items == null ? 0 : items.size();
        int from = Math.min((page - 1) * pageSize, total);
        int to = Math.min(from + pageSize, total);
        return new PageResult<>(items == null ? List.of() : items.subList(from, to), total, page, pageSize);
    }
}
