package com.xianda.freshdelivery.common;

public final class CurrentUserContext {
    public static final long DEFAULT_USER_ID = 1L;

    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();

    private CurrentUserContext() {
    }

    public static Long userId() {
        Long userId = USER_ID.get();
        if (userId == null) {
            throw new BusinessException(401, "用户未登录");
        }
        return userId;
    }

    public static void setUserId(Long userId) {
        USER_ID.set(userId);
    }

    public static void clear() {
        USER_ID.remove();
    }
}
