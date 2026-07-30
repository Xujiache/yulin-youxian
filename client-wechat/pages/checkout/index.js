const { yuan, lineAmount } = require("../../utils/format");
const { getAddresses } = require("../../api/addresses");
const { getCart } = require("../../api/cart");
const { getDeliverySlots } = require("../../api/delivery");
const { createOrder, payOrder, previewOrder } = require("../../api/orders");
const { requireCompleteProfile } = require("../../utils/auth-guard");
const { syncTheme } = require("../../utils/theme");
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
  deliveryFeeNotice: ""
};

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
    cartItemIds: [],
    buyNowRequest: null,
    loadError: "",
    payDisabled: true,
    paying: false,
    remark: ""
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
      ...EMPTY_AMOUNT
    });
    try {
      const [remoteAddresses, remoteSlots, cart] = await Promise.all([
        getAddresses(),
        getDeliverySlots(),
        getCart()
      ]);
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
        productAmountText: yuan(preview.productAmount),
        deliveryFeeText: yuan(preview.deliveryFee),
        packageFeeText: yuan(preview.packageFee),
        totalText: yuan(preview.payableAmount),
        deliveryFeeNotice: preview.deliveryFeeNotice || "",
        payDisabled: false,
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
      this.setData({
        address: preview.address,
        items: preview.items.map((item) => ({
          ...item,
          amountText: yuan(item.amount)
        })),
        productAmountText: yuan(preview.productAmount),
        deliveryFeeText: yuan(preview.deliveryFee),
        packageFeeText: yuan(preview.packageFee),
        totalText: yuan(preview.payableAmount),
        deliveryFeeNotice: preview.deliveryFeeNotice || "",
        payDisabled: false,
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
    const canPay = !this.data.payDisabled && this.data.address && this.data.activeSlotId && hasOrderSource(this);
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
      if (orderId && !isPaymentCancelled(error)) {
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
      wx.showModal({
        title: "支付失败",
        content: paymentErrorMessage(error, "支付失败，请重试"),
        showCancel: false
      });
    } finally {
      this.setData({ paying: false, payDisabled: false });
    }
  }
});
