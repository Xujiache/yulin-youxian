const { yuan, quantityText } = require("../../utils/format");
const { getOrder, getPaymentShare, payPaymentShare } = require("../../api/orders");
const { normalizeOrderItem } = require("../../api/normalize");
const { requireLogin } = require("../../utils/auth-guard");
const { syncTheme } = require("../../utils/theme");
const {
  isPaidOrder,
  isPaymentCancelled,
  paymentErrorMessage,
  requestWechatPayment
} = require("../../utils/wechat-payment");

function expireText(value) {
  if (!value) return "";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "";
  const pad = (number) => String(number).padStart(2, "0");
  return `请在 ${date.getMonth() + 1}月${date.getDate()}日 ${pad(date.getHours())}:${pad(date.getMinutes())} 前完成付款`;
}

Page({
  data: {
    glassMode: false,
    loading: true,
    token: "",
    ownerMode: false,
    orderId: 0,
    merchantName: "禹邻优鲜",
    amountText: "0.00",
    deliverySlotText: "",
    productAmountText: "0.00",
    deliveryFeeText: "0.00",
    packageFeeText: "0.00",
    items: [],
    totalItemCount: 0,
    expireText: "",
    loadError: "",
    paying: false,
    paymentFinished: false,
    ownerPaid: false
  },

  onLoad(options = {}) {
    const token = decodeURIComponent(options.token || "").trim();
    const ownerMode = options.owner === "1";
    const orderId = Number(options.orderId || 0);
    this.setData({ token, ownerMode, orderId });
    if (!token) {
      this.setData({ loading: false, loadError: "付款链接无效" });
      return;
    }
    if (ownerMode) {
      wx.showShareMenu({ withShareTicket: false, menus: ["shareAppMessage"] });
    }
    this.loadPaymentShare();
    if (ownerMode) {
      this.refreshOwnerOrder();
    }
  },

  onShow() {
    syncTheme(this);
    if (this.data.ownerMode && this.data.orderId) {
      this.refreshOwnerOrder();
    }
  },

  onShareAppMessage() {
    return {
      title: `请帮我支付 ¥${this.data.amountText}`,
      path: `/pages/pay-for-other/index?token=${encodeURIComponent(this.data.token)}`,
      imageUrl: "/assets/share/share-payment.jpg"
    };
  },

  async loadPaymentShare() {
    if (!this.data.token || this.data.paymentFinished) return;
    this.setData({ loading: true, loadError: "" });
    try {
      const share = await getPaymentShare(this.data.token);
      this.setData({
        merchantName: share.merchantName || "禹邻优鲜",
        amountText: yuan(share.payableAmount),
        deliverySlotText: share.deliverySlot || "",
        productAmountText: yuan(share.productAmount),
        deliveryFeeText: yuan(share.deliveryFee),
        packageFeeText: yuan(share.packageFee),
        items: (share.items || []).map((item) => {
          const normalized = normalizeOrderItem(item);
          return {
            ...normalized,
            quantityText: quantityText(normalized.quantity, normalized.saleUnit || ""),
            amountText: yuan(normalized.amount)
          };
        }),
        totalItemCount: (share.items || []).reduce((sum, item) => sum + Number(item.quantity || 0), 0),
        expireText: expireText(share.paymentExpireAt),
        loadError: ""
      });
    } catch (error) {
      this.setData({ loadError: error.message || "付款链接已失效" });
    } finally {
      this.setData({ loading: false });
    }
  },

  async refreshOwnerOrder() {
    if (!this.data.ownerMode || !this.data.orderId) return;
    const app = getApp();
    if (!app.isLoggedIn || !app.isLoggedIn()) return;
    try {
      const order = await getOrder(this.data.orderId);
      this.setData({ ownerPaid: isPaidOrder(order) });
    } catch {}
  },

  async handlePay() {
    if (this.data.ownerMode || this.data.paying || this.data.paymentFinished || this.data.loadError) return;
    const redirect = `/pages/pay-for-other/index?token=${encodeURIComponent(this.data.token)}`;
    if (!requireLogin(redirect)) return;
    this.setData({ paying: true });
    try {
      const payment = await payPaymentShare(this.data.token);
      await requestWechatPayment(payment);
      // 代付人不查询订单状态；支付结果和后续配送信息只由订单发起人查看。
      this.setData({ paymentFinished: true });
    } catch (error) {
      if (isPaymentCancelled(error)) {
        wx.showToast({ title: "已取消付款", icon: "none" });
        return;
      }
      wx.showModal({
        title: "付款未完成",
        content: paymentErrorMessage(error, "请稍后重试"),
        showCancel: false
      });
    } finally {
      this.setData({ paying: false });
    }
  },

  handleViewOrder() {
    if (this.data.ownerMode && this.data.orderId) {
      wx.redirectTo({ url: `/pages/order-detail/index?id=${this.data.orderId}` });
    }
  },

  handleBackHome() {
    wx.switchTab({ url: "/pages/home/index" });
  }
});
