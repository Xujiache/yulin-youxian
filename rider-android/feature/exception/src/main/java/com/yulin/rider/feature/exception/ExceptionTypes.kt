package com.yulin.rider.feature.exception

import com.yulin.rider.core.designsystem.FreshIconType

/**
 * 异常分组。
 *
 * 13 类平铺下来要占满整屏还得往下滚,骑手往往是一手拎货、站在雨里或楼道里操作,
 * 滚动着找选项太慢。按「是谁的问题」分成三组,一次只展开一组:
 * 这个判断骑手在打开页面之前脑子里就有了,分组只是把它变成一次点击。
 */
enum class ExceptionGroup(val label: String, val icon: FreshIconType) {
    CUSTOMER("顾客与地址", FreshIconType.PROFILE),
    GOODS("商品问题", FreshIconType.PACKAGE),
    LOGISTICS("骑手与门店", FreshIconType.RIDER),

    /** 只有「其他」一项,渲染成可直接点选的一行,不套折叠。 */
    OTHER("其他", FreshIconType.INFO),
    ;

    val kinds: List<ExceptionKind> get() = ExceptionKind.entries.filter { it.group == this }

    /** 折叠时显示在组名下面,不展开也知道里面有什么。 */
    val summary: String get() = kinds.joinToString("、") { it.shortLabel }
}

/**
 * 13 种异常类型,与后端 com.xianda.freshdelivery.delivery.common.ExceptionType 一一对应。
 * guidance 是本地兜底文案:断网提交时服务端给不了引导,总不能让骑手干等着。
 */
enum class ExceptionKind(
    val apiValue: String,
    val label: String,
    val icon: FreshIconType,
    val group: ExceptionGroup,
    /** 分组折叠时拼在一起预览,必须短到一行放得下。 */
    val shortLabel: String,
    val offlineGuidance: String,
) {
    CUSTOMER_UNREACHABLE("CUSTOMER_UNREACHABLE", "顾客联系不上", FreshIconType.PHONE, ExceptionGroup.CUSTOMER, "联系不上", "电话与短信各联系两次并间隔等待,仍无应答再拍照留证上报调度"),
    WRONG_ADDRESS("WRONG_ADDRESS", "地址错误", FreshIconType.MAP, ExceptionGroup.CUSTOMER, "地址错误", "与顾客核对正确地址,偏差过大时上报调度改派"),
    CUSTOMER_REFUSED("CUSTOMER_REFUSED", "顾客拒收", FreshIconType.CLOSE, ExceptionGroup.CUSTOMER, "拒收", "确认拒收原因并拍照留证,将商品带回门店走售后流程"),
    ACCESS_DENIED("ACCESS_DENIED", "门禁进不去", FreshIconType.LOCK, ExceptionGroup.CUSTOMER, "门禁", "联系顾客下楼自取或与物业沟通,仍无法进入时上报调度"),

    GOODS_DAMAGED("GOODS_DAMAGED", "商品破损", FreshIconType.PACKAGE, ExceptionGroup.GOODS, "破损", "现场拍照留证并安抚顾客,按售后流程上报处理"),
    GOODS_LEAKING("GOODS_LEAKING", "商品漏液", FreshIconType.WARNING, ExceptionGroup.GOODS, "漏液", "立即拍照留证并隔离漏液商品,避免污染其他订单后联系门店"),
    WEIGHT_DISPUTE("WEIGHT_DISPUTE", "缺斤少两争议", FreshIconType.STATS, ExceptionGroup.GOODS, "缺斤少两", "现场复秤并拍照留证,引导顾客走售后差价流程"),
    ITEM_MISSING("ITEM_MISSING", "缺件少件", FreshIconType.TASK, ExceptionGroup.GOODS, "缺件", "与门店核对拣货单确认缺件,缺件部分登记售后"),

    VEHICLE_FAILURE("VEHICLE_FAILURE", "车辆故障", FreshIconType.RIDER, ExceptionGroup.LOGISTICS, "车辆故障", "停靠安全位置并立即上报,剩余订单等待调度改派"),
    RIDER_UNWELL("RIDER_UNWELL", "身体不适", FreshIconType.ERROR, ExceptionGroup.LOGISTICS, "身体不适", "立即停止配送并上报,剩余订单交由调度改派"),
    BAD_WEATHER("BAD_WEATHER", "天气恶劣", FreshIconType.WARNING, ExceptionGroup.LOGISTICS, "天气恶劣", "确保安全放缓配送节奏,必要时上报调度延时或改派"),
    STORE_SLOW("STORE_SLOW", "门店出货慢", FreshIconType.STORE, ExceptionGroup.LOGISTICS, "出货慢", "向门店确认出货时间并上报,超时可申请先配其他任务"),

    OTHER("OTHER", "其他", FreshIconType.INFO, ExceptionGroup.OTHER, "其他", "描述具体情况并上报调度,等待人工处理"),
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
