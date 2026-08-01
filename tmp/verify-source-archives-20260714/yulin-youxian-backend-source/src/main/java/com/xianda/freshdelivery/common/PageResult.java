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
}
