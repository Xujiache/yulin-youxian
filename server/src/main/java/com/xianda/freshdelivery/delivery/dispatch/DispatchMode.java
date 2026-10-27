package com.xianda.freshdelivery.delivery.dispatch;

import java.util.Locale;

/**
 * 派单模式。
 *
 * 自营单店是「时段批次制」：商家自己备货、自己分单、按时段发车，
 * 系统不该在店主还没决定给谁的时候就把单派走。所以默认 {@link #ADVISORY}：
 * 打分、聚类、推荐照常算，但只在店主点下去时才生效。
 *
 * {@link #AUTO} 保留给「即时单、多骑手抢单」那种场景，切过去就是原来的全自动行为。
 */
public enum DispatchMode {

    /** 只推荐不执行：不自动派单、不自动改派。 */
    ADVISORY,

    /** 全自动：调度循环自行派单，超时风险时自动改派。 */
    AUTO;

    public boolean isAuto() {
        return this == AUTO;
    }

    /** 认不出来的值按 ADVISORY 处理：配置写错时宁可不派，也不能替店主做主。 */
    public static DispatchMode of(String raw) {
        if (raw == null || raw.isBlank()) {
            return ADVISORY;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return ADVISORY;
        }
    }
}
