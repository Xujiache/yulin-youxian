package com.yulin.rider.feature.exception

import com.yulin.rider.core.designsystem.FreshIconType

/**
 * 13 种异常类型,与后端 com.xianda.freshdelivery.delivery.common.ExceptionType 一一对应。
 * guidance 是本地兜底文案:断网提交时服务端给不了引导,总不能让骑手干等着。
 */
enum class ExceptionKind(
    val apiValue: String,
    val label: String,
    val icon: FreshIconType,
    val offlineGuidance: String,
) {
    CUSTOMER_UNREACHABLE("CUSTOMER_UNREACHABLE", "顾客联系不上", FreshIconType.PHONE, "电话与短信各联系两次并间隔等待,仍无应答再拍照留证上报调度"),
    WRONG_ADDRESS("WRONG_ADDRESS", "地址错误", FreshIconType.MAP, "与顾客核对正确地址,偏差过大时上报调度改派"),
    CUSTOMER_REFUSED("CUSTOMER_REFUSED", "顾客拒收", FreshIconType.CLOSE, "确认拒收原因并拍照留证,将商品带回门店走售后流程"),
    GOODS_DAMAGED("GOODS_DAMAGED", "商品破损", FreshIconType.PACKAGE, "现场拍照留证并安抚顾客,按售后流程上报处理"),
    GOODS_LEAKING("GOODS_LEAKING", "商品漏液", FreshIconType.WARNING, "立即拍照留证并隔离漏液商品,避免污染其他订单后联系门店"),
    WEIGHT_DISPUTE("WEIGHT_DISPUTE", "缺斤少两争议", FreshIconType.STATS, "现场复秤并拍照留证,引导顾客走售后差价流程"),
    ITEM_MISSING("ITEM_MISSING", "缺件少件", FreshIconType.TASK, "与门店核对拣货单确认缺件,缺件部分登记售后"),
    ACCESS_DENIED("ACCESS_DENIED", "门禁进不去", FreshIconType.LOCK, "联系顾客下楼自取或与物业沟通,仍无法进入时上报调度"),
    VEHICLE_FAILURE("VEHICLE_FAILURE", "车辆故障", FreshIconType.RIDER, "停靠安全位置并立即上报,剩余订单等待调度改派"),
    RIDER_UNWELL("RIDER_UNWELL", "身体不适", FreshIconType.ERROR, "立即停止配送并上报,剩余订单交由调度改派"),
    BAD_WEATHER("BAD_WEATHER", "天气恶劣", FreshIconType.WARNING, "确保安全放缓配送节奏,必要时上报调度延时或改派"),
    STORE_SLOW("STORE_SLOW", "门店出货慢", FreshIconType.STORE, "向门店确认出货时间并上报,超时可申请先配其他任务"),
    OTHER("OTHER", "其他", FreshIconType.INFO, "描述具体情况并上报调度,等待人工处理"),
    ;

    /** 破损/漏液/争议这类必须有图才说得清。 */
    val photoRequired: Boolean
        get() = this in setOf(GOODS_DAMAGED, GOODS_LEAKING, WEIGHT_DISPUTE, CUSTOMER_REFUSED, ITEM_MISSING)
}

/** 后端返回的 allowedNextActions 映射成骑手看得懂的话。 */
fun nextActionLabel(action: String): String = when (action) {
    "CONTINUE" -> "继续配送其他订单"
    "RETURN" -> "申请退回门店"
    "REASSIGN" -> "申请改派他人"
    else -> action
}
