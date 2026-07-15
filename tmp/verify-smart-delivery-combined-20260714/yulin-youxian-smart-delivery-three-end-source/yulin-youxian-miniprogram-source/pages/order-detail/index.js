const { yuan } = require("../../utils/format");
const { getHome } = require("../../api/catalog");
const { getOrder } = require("../../api/orders");
const { syncTheme } = require("../../utils/theme");
const DEFAULT_CONTACT_PHONE = "400-800-1234";
function buildRefundNotice(order) {
    if (!order || order.latestRefundStatus !== "已拒绝") {
        return "";
    }
    return `退款申请未通过：${order.latestRefundReason || "请联系门店客服了解原因"}`;
}
Page({
    data: {
        glassMode: false,
        loading: true,
        address: {},
        items: [],
        order: null,
        orderId: null,
        orderNo: "",
        statusText: "",
        deliverySlotText: "",
        productAmountText: "0.00",
        deliveryFeeText: "0.00",
        packageFeeText: "0.00",
        payableText: "0.00",
        refundedText: "0.00",
        hasRefundedAmount: false,
        refundNotice: "",
        refundRecords: [],
        contactPhone: DEFAULT_CONTACT_PHONE
    },
    async onLoad(options) {
        syncTheme(this);
        this.loadContactPhone();
        const id = Number(options.id || 0);
        if (!id) {
            this.setData({ loading: false });
            wx.showToast({ title: "订单不存在", icon: "none" });
            return;
        }
        try {
            const order = await getOrder(id);
            this.setData({
                order,
                orderId: order.id,
                orderNo: order.orderNo,
                statusText: order.status,
                address: order.address || {},
                deliverySlotText: order.deliverySlot || "",
                items: (order.items || []).map((item) => ({
                    ...item,
                    amountText: yuan(item.amount)
                })),
                productAmountText: yuan(order.productAmount),
                deliveryFeeText: yuan(order.deliveryFee),
                packageFeeText: yuan(order.packageFee),
                payableText: yuan(order.payableAmount),
                refundedText: yuan(order.refundedAmount),
                hasRefundedAmount: Number(order.refundedAmount || 0) > 0,
                refundNotice: buildRefundNotice(order),
                refundRecords: (order.refunds || []).map((refund) => ({
                    ...refund,
                    amountText: yuan(refund.refundAmount),
                    sourceText: refund.source === "ADMIN" ? "管理员发起" : "用户申请",
                    createdAtText: refund.createdAt ? refund.createdAt.replace("T", " ").slice(0, 19) : ""
                }))
            });
            return;
        }
        catch {
            wx.showToast({ title: "订单详情加载失败", icon: "none" });
        }
        finally {
            this.setData({ loading: false });
        }
    },
    async loadContactPhone() {
        try {
            const home = await getHome();
            this.setData({ contactPhone: home.contactPhone || DEFAULT_CONTACT_PHONE });
        }
        catch { }
    },
    handleRefund() {
        const id = this.data.orderId;
        if (!id) {
            wx.showToast({ title: "订单不存在", icon: "none" });
            return;
        }
        wx.navigateTo({ url: `/pages/refund-apply/index?orderId=${id}` });
    },
    handleService() {
        const phone = this.data.contactPhone || DEFAULT_CONTACT_PHONE;
        wx.showModal({
            title: "联系客服",
            content: `禹邻优鲜客服电话：${phone}`,
            confirmText: "拨打电话",
            cancelText: "取消",
            success(result) {
                if (!result.confirm) {
                    return;
                }
                wx.makePhoneCall({
                    phoneNumber: phone,
                    fail() {
                        wx.showToast({ title: "拨号失败，请稍后重试", icon: "none" });
                    }
                });
            }
        });
    }
});
