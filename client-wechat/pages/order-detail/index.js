const { yuan } = require("../../utils/format");
const { getHome } = require("../../api/catalog");
const { confirmDevelopmentPayment, getOrder, payOrder } = require("../../api/orders");
const { syncTheme } = require("../../utils/theme");
const {
  isPaidOrder,
  isPaymentCancelled,
  isPendingPaymentOrder,
  requestWechatPayment,
  waitForPaymentResult
} = require("../../utils/wechat-payment");

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
    contactPhone: DEFAULT_CONTACT_PHONE,
    isPendingPayment: false,
    paying: false,
    paymentNotice: ""
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
        isPendingPayment: isPendingPaymentOrder(order),
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
    } catch {
      wx.showToast({ title: "订单详情加载失败", icon: "none" });
    } finally {
      this.setData({ loading: false });
    }
  },

  onShow() {
    syncTheme(this);
    if (this.data.orderId && !this.data.loading && !this.data.paying) {
      this.refreshOrder();
    }
  },

  async refreshOrder() {
    try {
      const order = await getOrder(this.data.orderId);
      this.setData({
        order,
        statusText: order.status,
        isPendingPayment: isPendingPaymentOrder(order),
        paymentNotice: ""
      });
    } catch {}
  },

  async handlePay() {
    if (!this.data.orderId || this.data.paying || !this.data.isPendingPayment) {
      return;
    }
    this.setData({ paying: true, paymentNotice: "" });
    try {
      const payment = await payOrder(this.data.orderId);
      const paymentResult = await requestWechatPayment(payment);
      if (paymentResult && paymentResult.developmentMode) {
        await confirmDevelopmentPayment(this.data.orderId);
      } else {
        const order = await waitForPaymentResult(this.data.orderId);
        if (!isPaidOrder(order)) {
          this.setData({ paymentNotice: "支付结果还在确认中，请稍后刷新订单状态。" });
          return;
        }
      }
      await this.refreshOrder();
      wx.showToast({ title: "支付成功", icon: "success" });
    } catch (error) {
      if (this.data.orderId && !isPaymentCancelled(error)) {
        try {
          const order = await waitForPaymentResult(this.data.orderId, { attempts: 3, interval: 700 });
          if (isPaidOrder(order)) {
            await this.refreshOrder();
            wx.showToast({ title: "支付成功", icon: "success" });
            return;
          }
        } catch {}
      }
      if (isPaymentCancelled(error)) {
        this.setData({ paymentNotice: "支付已取消，订单仍可继续支付。" });
        return;
      }
      wx.showToast({ title: error.message || "支付失败，请稍后重试", icon: "none" });
    } finally {
      this.setData({ paying: false });
    }
  },

  async loadContactPhone() {
    try {
      const home = await getHome();
      this.setData({ contactPhone: home.contactPhone || DEFAULT_CONTACT_PHONE });
    } catch {}
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
