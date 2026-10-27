package com.xianda.freshdelivery.delivery.account;

import java.util.Optional;

public class NoopRiderTokenResolver implements RiderTokenResolver {
    // TODO 由 A1 的 RiderAuthService 实现替换，占位实现永远返回未登录
    @Override
    public Optional<Long> resolveRiderId(String authorizationHeader) {
        return Optional.empty();
    }
}
