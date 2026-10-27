package com.xianda.freshdelivery.delivery.common;

public final class DeliveryErrorCode {
    public static final int DELIVERY_DISABLED = 1000;
    public static final int RIDER_UNAUTHORIZED = 1001;
    public static final int RIDER_SUSPENDED = 1002;
    public static final int RIDER_OFF_DUTY = 1003;
    public static final int LOCATION_CONSENT_REQUIRED = 1004;
    public static final int TASK_NOT_FOUND = 1010;
    public static final int TASK_STATUS_NOT_ALLOWED = 1011;
    public static final int TASK_NOT_OWNED_BY_RIDER = 1012;
    public static final int ORDER_NOT_DISPATCHABLE = 1013;
    public static final int TASK_ALREADY_EXISTS = 1014;
    /** 当前是推荐模式，系统不自动派单。 */
    public static final int DISPATCH_ADVISORY_ONLY = 1015;
    public static final int NO_AVAILABLE_RIDER = 1020;
    public static final int RIDER_CONCURRENCY_LIMIT = 1021;
    public static final int RIDER_FATIGUE_SUSPENDED = 1022;
    public static final int ROUTE_PLAN_FAILED = 1030;
    public static final int ADDRESS_MISSING_COORDINATE = 1031;
    public static final int LOCATION_REJECTED = 1040;
    public static final int PRIVACY_NUMBER_UNAVAILABLE = 1050;
    public static final int EVIDENCE_UPLOAD_FAILED = 1060;
    public static final int APP_RELEASE_NOT_FOUND = 1070;
    public static final int APP_RELEASE_STATUS_NOT_ALLOWED = 1071;
    public static final int APP_RELEASE_INVALID = 1072;

    private DeliveryErrorCode() {
    }

    public static String messageOf(int code) {
        return switch (code) {
            case DELIVERY_DISABLED -> "配送功能已整体停用";
            case RIDER_UNAUTHORIZED -> "骑手未登录或令牌失效";
            case RIDER_SUSPENDED -> "骑手账号被停用";
            case RIDER_OFF_DUTY -> "骑手未上班";
            case LOCATION_CONSENT_REQUIRED -> "未取得定位授权同意";
            case TASK_NOT_FOUND -> "配送任务不存在";
            case TASK_STATUS_NOT_ALLOWED -> "任务状态不允许该操作";
            case TASK_NOT_OWNED_BY_RIDER -> "任务不属于当前骑手";
            case ORDER_NOT_DISPATCHABLE -> "订单状态不满足派单条件";
            case TASK_ALREADY_EXISTS -> "该订单已存在配送任务";
            case DISPATCH_ADVISORY_ONLY -> "当前是推荐模式，系统不会自动派单";
            case NO_AVAILABLE_RIDER -> "无可用骑手";
            case RIDER_CONCURRENCY_LIMIT -> "骑手已达并发上限";
            case RIDER_FATIGUE_SUSPENDED -> "骑手处于疲劳停派期";
            case ROUTE_PLAN_FAILED -> "路径规划失败";
            case ADDRESS_MISSING_COORDINATE -> "地址缺少经纬度";
            case LOCATION_REJECTED -> "位置数据被拒";
            case PRIVACY_NUMBER_UNAVAILABLE -> "隐私号服务不可用";
            case EVIDENCE_UPLOAD_FAILED -> "凭证上传失败";
            case APP_RELEASE_NOT_FOUND -> "版本记录不存在";
            case APP_RELEASE_STATUS_NOT_ALLOWED -> "当前版本状态不允许该操作";
            case APP_RELEASE_INVALID -> "安装包校验失败";
            default -> "配送服务异常";
        };
    }
}
