const { yuan, quantityText } = require("../../utils/format");
const { getOrder, getPaymentShare, payPaymentShare } = require("../../api/orders");
const { normalizeOrderItem } = require("../../api/normalize");
const { requireLogin } = require("../../utils/auth-guard");
const { navigateHome } = require("../../utils/navigation");
const { syncTheme } = require("../../utils/theme");
const {
  isPaidOrder,
  isPaymentCancelled,
  paymentErrorMessage,
  requestWechatPayment,
  waitForPaymentShareResult
} = require("../../utils/wechat-payment");

function expireText(value) {
  if (!value) return "";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "";
  const pad = (number) => String(number).padStart(2, "0");
  return `请在 ${date.getMonth() + 1}月${date.getDate()}日 ${pad(date.getHours())}:${pad(date.getMinutes())} 前完成付款`;
}

function normalizeDiscountDetails(share, discountAmount) {
  const rawDetails = Array.isArray(share.discountDetails)
    ? share.discountDetails
    : Array.isArray(share.discounts)
      ? share.discounts
      : [];
  const details = rawDetails.map((detail, index) => {
    const amount = Number(
      detail.discountAmount !== undefined
        ? detail.discountAmount
        : detail.amount
    );
    return {
      id: detail.id || `${index}-${detail.name || detail.title || "discount"}`,
      name: detail.name || detail.title || detail.label || "优惠减免",
      amountText: Number.isFinite(amount) ? yuan(amount) : "",
      hasAmount: Number.isFinite(amount)
    };
  });
  if (!details.length && discountAmount > 0) {
    details.push({
      id: "lottery-discount",
      name: share.discountName || "分享抽奖减免",
      amountText: yuan(discountAmount),
      hasAmount: true
    });
  }
  return details;
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
    discountAmountText: "0.00",
    hasDiscount: false,
    discountDetails: [],
    lotteryPrizeName: "",
    hasLotteryPrize: false,
    items: [],
    totalItemCount: 0,
    expireText: "",
    loadError: "",
    paying: false,
    paymentConfirming: false,
    paymentConfirmationText: "",
    paymentFinished: false,
    ownerPaid: false
  },

  onLoad(options = {}) {
    this._pageActive = true;
    this._paymentConfirmationToken = 0;
    this._confirmingPaymentResult = false;
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
    if (!this.data.ownerMode && this.data.paymentConfirming && !this.data.paymentFinished) {
      this.confirmPaymentResult({ attempts: 3, interval: 1200 });
    }
  },

  onUnload() {
    this._pageActive = false;
    this._paymentConfirmationToken += 1;
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
      const lotteryResult = share.lotteryResult || share.lottery || {};
      const discountAmount = Number(
        share.discountAmount !== undefined
          ? share.discountAmount
          : lotteryResult.discountAmount || 0
      );
      const lotteryPrizeName = share.lotteryPrizeName
        || share.prizeName
        || lotteryResult.prizeName
        || "";
      this.setData({
        merchantName: share.merchantName || "禹邻优鲜",
        amountText: yuan(share.payableAmount),
        deliverySlotText: share.deliverySlot || "",
        productAmountText: yuan(share.productAmount),
        deliveryFeeText: yuan(share.deliveryFee),
        packageFeeText: yuan(share.packageFee),
        discountAmountText: yuan(discountAmount),
        hasDiscount: discountAmount > 0,
        discountDetails: normalizeDiscountDetails(share, discountAmount),
        lotteryPrizeName,
        hasLotteryPrize: Boolean(lotteryPrizeName),
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
    if (
      this.data.ownerMode
      || this.data.paying
      || this.data.paymentConfirming
      || this.data.paymentFinished
      || this.data.loadError
    ) return;
    const redirect = `/pages/pay-for-other/index?token=${encodeURIComponent(this.data.token)}`;
    if (!requireLogin(redirect)) return;
    this.setData({ paying: true });
    try {
      const payment = await payPaymentShare(this.data.token);
      await requestWechatPayment(payment);
      this.setData({
        paymentConfirming: true,
        paymentConfirmationText: "支付结果确认中，请勿重复付款。"
      });
      await this.confirmPaymentResult();
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

  async confirmPaymentResult(options = {}) {
    if (
      !this.data.token
      || this.data.paymentFinished
      || this._confirmingPaymentResult
    ) {
      return;
    }
    this._confirmingPaymentResult = true;
    const confirmationToken = this._paymentConfirmationToken + 1;
    this._paymentConfirmationToken = confirmationToken;
    this.setData({
      paymentConfirming: true,
      paymentConfirmationText: "支付结果确认中，请勿重复付款。"
    });
    try {
      const result = await waitForPaymentShareResult(this.data.token, {
        attempts: options.attempts || 8,
        interval: options.interval || 1000
      });
      if (!this._pageActive || confirmationToken !== this._paymentConfirmationToken) {
        return;
      }
      if (result.confirmed) {
        this.setData({
          paymentConfirming: false,
          paymentConfirmationText: "",
          paymentFinished: true
        });
        return;
      }
      this.setData({
        paymentConfirming: true,
        paymentConfirmationText: result.state === "PENDING"
          ? "微信支付结果仍在同步，请稍后重新确认；请勿重复付款。"
          : "暂时无法连接服务端确认结果，请检查网络后重试；请勿重复付款。"
      });
    } finally {
      this._confirmingPaymentResult = false;
    }
  },

  handleConfirmPayment() {
    this.confirmPaymentResult({ attempts: 4, interval: 1200 });
  },

  handleViewOrder() {
    if (this.data.ownerMode && this.data.orderId) {
      wx.redirectTo({ url: `/pages/order-detail/index?id=${this.data.orderId}` });
    }
  },

  handleBackHome() {
    navigateHome(wx);
  }
});
