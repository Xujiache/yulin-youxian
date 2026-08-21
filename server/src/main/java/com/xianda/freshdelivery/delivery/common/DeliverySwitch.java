package com.xianda.freshdelivery.delivery.common;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 配送域总闸(delivery.enabled / 环境变量 DELIVERY_ENABLED),部署文档里的二级回退手段。
 *
 * <p>关掉后整个配送域停止工作:不再生成配送任务、调度循环空转、骑手端写接口一律拒绝,
 * 订单回到「店主自己送」的老模式。与 dispatch.enabled 语义不同 ——
 * 后者只停自动派单,任务照建、骑手端照常用,人工指派仍然可以。
 *
 * <p>总闸只能改配置 + 重启,不能在后台页面上点,这是故意的:
 * 它是出事时的应急闸,不该被误触。
 */
@Component
public class DeliverySwitch {

    /** 拒绝写操作时回给调用方的文案:要让店主一眼看出是被总闸关了,而不是系统坏了。 */
    public static final String DISABLED_MESSAGE =
            "配送功能已整体停用（delivery.enabled=false），订单请按老流程自行配送。"
                    + "如需恢复，请把 DELIVERY_ENABLED 改回 true 并重启后端服务。";

    private final ObjectProvider<DeliveryProperties> deliveryProperties;

    public DeliverySwitch(ObjectProvider<DeliveryProperties> deliveryProperties) {
        this.deliveryProperties = deliveryProperties;
    }

    /** 给不关心总闸的单元测试用:等价于总闸常开。 */
    public static DeliverySwitch alwaysEnabled() {
        return new DeliverySwitch(null);
    }

    /** 配置缺失时按「开」处理,避免拿不到配置就把整个配送域误关。 */
    public boolean enabled() {
        DeliveryProperties properties = deliveryProperties == null ? null : deliveryProperties.getIfAvailable();
        return properties == null || properties.enabled();
    }

    public void ensureEnabled() {
        if (!enabled()) {
            throw new DeliveryException(DeliveryErrorCode.DELIVERY_DISABLED, DISABLED_MESSAGE);
        }
    }
}
