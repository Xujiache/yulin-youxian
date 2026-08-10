const { yuan } = require("../../utils/format");
const { getAddresses } = require("../../api/addresses");
const { getCart } = require("../../api/cart");
const { getHome } = require("../../api/catalog");
const { getDeliverySlots } = require("../../api/delivery");
const { createOrder, createPaymentShare, payOrder, previewOrder } = require("../../api/orders");
const { requireCompleteProfile } = require("../../utils/auth-guard");
const { syncTheme } = require("../../utils/theme");
const { buildMinOrderState, normalizeMinOrderAmount } = require("../../utils/min-order");
const {
  isPaidOrder,
  isPaymentCancelled,
  paymentErrorMessage,
  requestWechatPayment,
  waitForPaymentResult
} = require("../../utils/wechat-payment");

function showPaymentPending(page, orderId) {
  page.setData({ paying: false, payDisabled: false });
  wx.showModal({
    title: "\u652f\u4ed8\u5904\u7406\u4e2d",
    content: "\u5fae\u4fe1\u5df2\u8fd4\u56de\u652f\u4ed8\u7ed3\u679c\uff0c\u8ba2\u5355\u72b6\u6001\u8fd8\u5728\u786e\u8ba4\u4e2d\uff0c\u8bf7\u5230\u8ba2\u5355\u67e5\u770b\u6700\u65b0\u72b6\u6001\u3002",
    confirmText: "\u67e5\u770b\u8ba2\u5355",
    cancelText: "\u7559\u5728\u5f53\u524d",
    success(result) {
      if (result.confirm && orderId) {
        wx.redirectTo({ url: `/pages/order-detail/index?id=${orderId}` });
      }
    }
  });
}

function showPaymentCancelled(page, orderId) {
  page.setData({ paying: false, payDisabled: false });
  wx.showModal({
    title: "\u652f\u4ed8\u5df2\u53d6\u6d88",
    content: "\u8ba2\u5355\u5df2\u4fdd\u7559\uff0c\u4f60\u53ef\u4ee5\u5728\u8ba2\u5355\u8be6\u60c5\u4e2d\u7ee7\u7eed\u652f\u4ed8\u3002",
    confirmText: "\u67e5\u770b\u8ba2\u5355",
    cancelText: "\u7ee7\u7eed\u8d2d\u7269",
    success(result) {
      if (result.confirm && orderId) {
        wx.redirectTo({ url: `/pages/order-detail/index?id=${orderId}` });
      }
    }
  });
}

const EMPTY_AMOUNT = {
  productAmountText: "0.00",
  deliveryFeeText: "0.00",
  packageFeeText: "0.00",
  totalText: "0.00",
  deliveryFeeNotice: "",
  minOrderAmount: 0,
  minOrderText: "",
  minOrderTip: "",
  minOrderMet: true,
  shortfallText: "",
  payLabel: "微信支付"
};

const PAYMENT_METHODS = [
  {
    code: "WECHAT",
    title: "微信支付",
    desc: "使用当前微信账号付款",
    icon: "/assets/payment/wechat-pay.png"
  },
  {
    code: "FRIEND",
    title: "请好友付款",
    desc: "生成代付链接，好友使用自己的微信付款",
    icon: "/assets/payment/friend-pay.png"
  }
];

function paymentButtonLabel(paymentMethod, minOrderMet, shortfallText) {
  if (!minOrderMet) {
    return `还差¥${shortfallText}起送`;
  }
  return paymentMethod === "FRIEND" ? "提交订单并分享" : "微信支付";
}

function applyPreviewAmounts(preview, fallbackMinOrderAmount = 0) {
  const minOrderAmount = normalizeMinOrderAmount(
    preview && preview.minOrderAmount != null ? preview.minOrderAmount : fallbackMinOrderAmount
  );
  const minOrder = buildMinOrderState(preview ? preview.productAmount : 0, minOrderAmount);
  return {
    productAmountText: yuan(preview ? preview.productAmount : 0),
    deliveryFeeText: yuan(preview ? preview.deliveryFee : 0),
    packageFeeText: yuan(preview ? preview.packageFee : 0),
    totalText: yuan(preview ? preview.payableAmount : 0),
    deliveryFeeNotice: preview && preview.deliveryFeeNotice ? preview.deliveryFeeNotice : "",
    minOrderAmount: minOrder.minOrderAmount,
    minOrderText: minOrder.minOrderText,
    minOrderTip: minOrder.minOrderTip,
    minOrderMet: minOrder.minOrderMet,
    shortfallText: minOrder.shortfallText,
    payLabel: minOrder.payLabel
  };
}

function orderSourcePayload(page) {
  if (page.data.buyNowRequest) {
    return { ...page.data.buyNowRequest };
  }
  return { cartItemIds: page.data.cartItemIds };
}

function hasOrderSource(page) {
  return Boolean(page.data.buyNowRequest || page.data.cartItemIds.length);
}

Page({
  data: {
    glassMode: false,
    loading: true,
    address: null,
    items: [],
    slots: [],
    activeSlotId: 0,
    productAmountText: "0.00",
    deliveryFeeText: "5.00",
    packageFeeText: "1.00",
    totalText: "0.00",
    deliveryFeeNotice: "",
    minOrderAmount: 0,
    minOrderText: "",
    minOrderTip: "",
    minOrderMet: true,
    shortfallText: "",
    payLabel: "微信支付",
    cartItemIds: [],
    buyNowRequest: null,
    loadError: "",
    payDisabled: true,
    paying: false,
    remark: "",
    paymentMethods: PAYMENT_METHODS,
    paymentMethod: "WECHAT"
  },

  onLoad(options = {}) {
    const isBuyNow = options.buyNow === "1" && Number(options.productId) > 0 && Number(options.quantity) > 0;
    const buyNowRequest = isBuyNow
      ? {
          productId: Number(options.productId),
          ...(Number(options.skuId) > 0 ? { skuId: Number(options.skuId) } : {}),
          quantity: Number(options.quantity)
        }
      : null;
    this.setData({ buyNowRequest });
    if (!requireCompleteProfile("/pages/checkout/index")) {
      this.setData({ loading: false });
      return;
    }
    this.loadCheckout();
  },

  onShow() {
    syncTheme(this);
    const selectedAddress = wx.getStorageSync("checkoutSelectedAddress");
    if (!selectedAddress || !selectedAddress.id) {
      return;
    }
    wx.removeStorageSync("checkoutSelectedAddress");
    if (!hasOrderSource(this) || !this.data.activeSlotId) {
      this.setData({ address: selectedAddress });
      this.loadCheckout();
      return;
    }
    this.refreshPreview(selectedAddress, this.data.activeSlotId);
  },

  async loadCheckout() {
    const buyNowRequest = this.data.buyNowRequest;
    this.setData({
      loading: true,
      loadError: "",
      payDisabled: true,
      address: null,
      items: [],
      slots: [],
      activeSlotId: 0,
      cartItemIds: [],
      buyNowRequest,
      remark: "",
      paying: false,
      paymentMethod: "WECHAT",
      ...EMPTY_AMOUNT
    });
    try {
      const [remoteAddresses, remoteSlots, cart, home] = await Promise.all([
        getAddresses(),
        getDeliverySlots(),
        getCart(),
        getHome().catch(() => null)
      ]);
      const fallbackMinOrderAmount = normalizeMinOrderAmount(home && home.minOrderAmount);
      const selectedItems = buyNowRequest
        ? []
        : (cart.items || []).filter((item) => item.selected);
      const storedAddress = wx.getStorageSync("checkoutSelectedAddress");
      if (storedAddress && storedAddress.id) {
        wx.removeStorageSync("checkoutSelectedAddress");
      }
      const address = (storedAddress && storedAddress.id)
        ? storedAddress
        : remoteAddresses.find((item) => item.isDefault) || remoteAddresses[0];
      const availableSlots = (remoteSlots || []).filter((item) => item && item.available !== false);
      const slot = availableSlots[0];
      const cartItemIds = selectedItems.map((item) => item.id);
      if (!cartItemIds.length && !buyNowRequest) {
        this.setData({
          address: address || null,
          slots: availableSlots,
          items: [],
          cartItemIds: [],
          payDisabled: true,
          loadError: "购物车还没有选中的商品，请先选择后再结算。",
          ...EMPTY_AMOUNT
        });
        wx.showToast({ title: "请先选择商品", icon: "none" });
        return;
      }
      if (!address) {
        this.setData({
          address: null,
          slots: availableSlots,
          activeSlotId: slot ? slot.id : 0,
          cartItemIds,
          payDisabled: true,
          loadError: ""
        });
        wx.showToast({ title: "请先添加收货地址", icon: "none" });
        wx.navigateTo({ url: "/pages/address/index?select=1" });
        return;
      }
      if (!slot) {
        this.setData({
          address: address || null,
          slots: [],
          items: [],
          cartItemIds,
          payDisabled: true,
          loadError: "暂无可预约配送时间",
          ...EMPTY_AMOUNT
        });
        wx.showToast({ title: "暂无可预约配送时间", icon: "none" });
        return;
      }
      const preview = await previewOrder({
        addressId: address.id,
        deliverySlotId: slot.id,
        ...(buyNowRequest || { cartItemIds })
      });
      const amounts = applyPreviewAmounts(preview, fallbackMinOrderAmount);
      this.setData({
        address: preview.address,
        slots: availableSlots,
        activeSlotId: slot.id,
        items: preview.items.map((item) => ({
          ...item,
          amountText: yuan(item.amount)
        })),
        cartItemIds,
        buyNowRequest,
        ...amounts,
        payLabel: paymentButtonLabel("WECHAT", amounts.minOrderMet, amounts.shortfallText),
        payDisabled: !amounts.minOrderMet,
        loadError: ""
      });
      return;
    } catch (error) {
      const message = error && error.message ? error.message : "订单信息加载失败，请稍后重试。";
      this.setData({
        address: null,
        items: [],
        slots: [],
        activeSlotId: 0,
        cartItemIds: [],
        buyNowRequest,
        payDisabled: true,
        loadError: message,
        ...EMPTY_AMOUNT
      });
      wx.showToast({ title: message, icon: "none" });
    } finally {
      this.setData({ loading: false });
    }
  },

  handleChooseAddress() {
    const selectedId = this.data.address ? this.data.address.id : 0;
    wx.navigateTo({ url: `/pages/address/index?select=1&selectedId=${selectedId}` });
  },

  async refreshPreview(address, activeSlotId) {
    if (!address || !address.id || !activeSlotId || !hasOrderSource(this)) {
      return;
    }
    try {
      const preview = await previewOrder({
        addressId: address.id,
        deliverySlotId: activeSlotId,
        ...orderSourcePayload(this)
      });
      const amounts = applyPreviewAmounts(preview, this.data.minOrderAmount);
      this.setData({
        address: preview.address,
        items: preview.items.map((item) => ({
          ...item,
          amountText: yuan(item.amount)
        })),
        ...amounts,
        payLabel: paymentButtonLabel(this.data.paymentMethod, amounts.minOrderMet, amounts.shortfallText),
        payDisabled: !amounts.minOrderMet,
        loadError: ""
      });
    } catch (error) {
      this.setData({ payDisabled: true });
      wx.showToast({ title: error.message || "地址选择失败", icon: "none" });
    }
  },

  handleRetry() {
    this.loadCheckout();
  },

  handleRemarkInput(event) {
    this.setData({ remark: event.detail.value || "" });
  },

  handlePaymentMethod(event) {
    if (this.data.paying) return;
    const paymentMethod = event.currentTarget.dataset.method === "FRIEND" ? "FRIEND" : "WECHAT";
    this.setData({
      paymentMethod,
      payLabel: paymentButtonLabel(paymentMethod, this.data.minOrderMet, this.data.shortfallText)
    });
  },

  goCart() {
    wx.redirectTo({ url: "/pages/cart/index" });
  },

  async chooseSlot(event) {
    const activeSlotId = Number(event.currentTarget.dataset.id);
    this.setData({ activeSlotId });
    if (!this.data.address || !hasOrderSource(this)) {
      return;
    }
    await this.refreshPreview(this.data.address, activeSlotId);
  },

  async handlePay() {
    if (!requireCompleteProfile("/pages/checkout/index")) {
      return;
    }
    if (this.data.paying) {
      return;
    }
    if (!this.data.minOrderMet) {
      wx.showToast({
        title: this.data.minOrderTip || "未满起送价",
        icon: "none"
      });
      return;
    }
    const canPay = !this.data.payDisabled && this.data.address && this.data.activeSlotId && hasOrderSource(this);
    const paymentMethod = this.data.paymentMethod;
    this.setData({ paying: true, payDisabled: true });
    let orderId = null;
    if (!canPay) {
      this.setData({ paying: false, payDisabled: true });
      wx.showToast({ title: "订单信息不完整", icon: "none" });
      return;
    }
    try {
      const order = await createOrder({
        addressId: this.data.address.id,
        deliverySlotId: this.data.activeSlotId,
        ...orderSourcePayload(this),
        remark: (this.data.remark || "").trim()
      });
      orderId = order.id;
      if (paymentMethod === "FRIEND") {
        const share = await createPaymentShare(order.id);
        wx.redirectTo({
          url: `/pages/pay-for-other/index?token=${encodeURIComponent(share.token)}&owner=1&orderId=${order.id}`
        });
        return;
      }
      const payment = await payOrder(order.id);
      await requestWechatPayment(payment);
      const latestOrder = await waitForPaymentResult(order.id);
      if (!isPaidOrder(latestOrder)) {
        showPaymentPending(this, order.id);
        return;
      }
      wx.showToast({ title: "支付成功", icon: "success" });
      wx.redirectTo({ url: `/pages/order-detail/index?id=${order.id}` });
    } catch (error) {
      if (orderId && paymentMethod === "WECHAT" && !isPaymentCancelled(error)) {
        try {
          const latestOrder = await waitForPaymentResult(orderId, { attempts: 3, interval: 700 });
          if (isPaidOrder(latestOrder)) {
            wx.showToast({ title: "支付成功", icon: "success" });
            wx.redirectTo({ url: `/pages/order-detail/index?id=${orderId}` });
            return;
          }
        } catch {}
      }
      if (isPaymentCancelled(error)) {
        showPaymentCancelled(this, orderId);
        return;
      }
      const message = paymentErrorMessage(error, "支付失败，请重试");
      if (String(message || "").indexOf("起送") >= 0) {
        wx.showToast({ title: message, icon: "none" });
        this.setData({ paying: false, payDisabled: true });
        return;
      }
      wx.showModal({
        title: "支付失败",
        content: message,
        showCancel: false
      });
    } finally {
      this.setData({
        paying: false,
        payDisabled: !this.data.minOrderMet
      });
    }
  }
});
