package com.xianda.freshdelivery.delivery.common;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("delivery")
public record DeliveryProperties(
        // 配送域总闸。缺省必须是 true:上下文里没配 delivery.* 时(如单元测试)
        // record 绑定会把 boolean 补成 false，等于悄悄把整个配送域关掉。
        @DefaultValue("true") boolean enabled,
        Store store,
        Amap amap,
        Push push,
        PrivacyNumber privacyNumber,
        Dispatch dispatch,
        Tracking tracking,
        Upload upload
) {
    public record Store(Double lat, Double lng) {
    }

    public record Amap(String webKey, String jsKey, boolean enabled) {
    }

    public record Push(String provider, String jpushAppKey, String jpushMasterSecret) {
    }

    public record PrivacyNumber(
            String provider,
            String aliyunAccessKeyId,
            String aliyunAccessKeySecret,
            String poolKey
    ) {
    }

    public record Dispatch(boolean loopEnabled, long loopIntervalMs) {
    }

    public record Tracking(String cleanupCron, String cleanupZone) {
    }

    public record Upload(String deliveryPath) {
    }
}
