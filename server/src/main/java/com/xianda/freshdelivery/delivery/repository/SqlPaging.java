package com.xianda.freshdelivery.delivery.repository;

import java.util.Map;

public final class SqlPaging {
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 200;

    private SqlPaging() {
    }

    public static int page(Integer page) {
        return page == null || page < 1 ? 1 : page;
    }

    public static int pageSize(Integer pageSize) {
        if (pageSize == null || pageSize < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    public static int offset(Integer page, Integer pageSize) {
        return (page(page) - 1) * pageSize(pageSize);
    }

    public static String orderBy(String requested, Map<String, String> whitelist, String fallbackColumn, boolean descending) {
        String column = whitelist.get(requested == null ? "" : requested.trim());
        if (column == null) {
            column = fallbackColumn;
        }
        return " ORDER BY " + column + (descending ? " DESC" : " ASC");
    }

    public static String likeArgument(String keyword) {
        return "%" + keyword.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
}
