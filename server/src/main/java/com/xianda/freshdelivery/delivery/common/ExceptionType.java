package com.xianda.freshdelivery.delivery.common;

import java.util.List;

public enum ExceptionType {
    CUSTOMER_UNREACHABLE("顾客联系不上", "电话与短信各联系两次并间隔等待，仍无应答再拍照留证上报调度", List.of("CONTINUE", "RETURN", "REASSIGN")),
    WRONG_ADDRESS("地址错误", "与顾客核对正确地址，偏差过大时上报调度改派", List.of("CONTINUE", "REASSIGN", "RETURN")),
    CUSTOMER_REFUSED("顾客拒收", "确认拒收原因并拍照留证，将商品带回门店走售后流程", List.of("RETURN")),
    GOODS_DAMAGED("商品破损", "现场拍照留证并安抚顾客，按售后流程上报处理", List.of("CONTINUE", "RETURN")),
    GOODS_LEAKING("商品漏液", "立即拍照留证并隔离漏液商品，避免污染其他订单后联系门店", List.of("CONTINUE", "RETURN")),
    WEIGHT_DISPUTE("缺斤少两争议", "现场复秤并拍照留证，引导顾客走售后差价流程", List.of("CONTINUE", "RETURN")),
    ITEM_MISSING("缺件少件", "与门店核对拣货单确认缺件，缺件部分登记售后", List.of("CONTINUE", "RETURN")),
    ACCESS_DENIED("门禁进不去", "联系顾客下楼自取或与物业沟通，仍无法进入时上报调度", List.of("CONTINUE", "RETURN")),
    VEHICLE_FAILURE("车辆故障", "停靠安全位置并立即上报，剩余订单等待调度改派", List.of("REASSIGN")),
    RIDER_UNWELL("身体不适", "立即停止配送并上报，剩余订单交由调度改派", List.of("REASSIGN")),
    BAD_WEATHER("天气恶劣", "确保安全放缓配送节奏，必要时上报调度延时或改派", List.of("CONTINUE", "REASSIGN")),
    STORE_SLOW("门店出货慢", "向门店确认出货时间并上报，超时可申请先配其他任务", List.of("CONTINUE", "REASSIGN")),
    OTHER("其他", "描述具体情况并上报调度，等待人工处理", List.of("CONTINUE", "RETURN", "REASSIGN"));

    private final String displayName;
    private final String defaultGuidance;
    private final List<String> defaultNextActions;

    ExceptionType(String displayName, String defaultGuidance, List<String> defaultNextActions) {
        this.displayName = displayName;
        this.defaultGuidance = defaultGuidance;
        this.defaultNextActions = defaultNextActions;
    }

    public String displayName() {
        return displayName;
    }

    public String defaultGuidance() {
        return defaultGuidance;
    }

    public List<String> defaultNextActions() {
        return defaultNextActions;
    }
}
