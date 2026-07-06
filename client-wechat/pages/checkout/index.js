const { yuan, lineAmount } = require("../../utils/format");
const { getAddresses } = require("../../api/addresses");
const { getCart } = require("../../api/cart");
const { getDeliverySlots } = require("../../api/delivery");
const { confirmDevelopmentPayment, createOrder, payOrder, previewOrder } = require("../../api/orders");
const { requireCompleteProfile } = require("../../utils/auth-guard");

function requestWechatPayment(payment) {
  if (!payment || payment.developmentMode) {
    return Promise.resolve({ developmentMode: true });
  }
  return new Promise((resolve, reject) => {
    wx.requestPayment({
      timeStamp: payment.timeStamp,
      nonceStr: payment.nonceStr,
      "package": payment.packageValue,
      signType: payment.signType,
      paySign: payment.paySign,
      success: resolve,
      fail: reject
    });
  });
}

Page({
  data: {
    loading: true,
    address: null,
    items: [],
    slots: [],
    activeSlotId: 0,
    productAmountText: "0.00",
    deliveryFeeText: "5.00",
    packageFeeText: "1.00",
    totalText: "0.00",
    cartItemIds: []
  },

  onLoad() {
    if (!requireCompleteProfile("/pages/checkout/index")) {
      this.setData({ loading: false });
      return;
    }
    this.loadCheckout();
  },

  async loadCheckout() {
    try {
      const [remoteAddresses, remoteSlots, cart] = await Promise.all([
        getAddresses(),
        getDeliverySlots(),
        getCart()
      ]);
      const selectedItems = (cart.items || []).filter((item) => item.selected);
      const address = remoteAddresses.find((item) => item.isDefault) || remoteAddresses[0];
      const slot = remoteSlots[0];
      const cartItemIds = selectedItems.map((item) => item.id);
      if (!cartItemIds.length) {
        this.setData({
          address: address || null,
          slots: remoteSlots,
          items: [],
          cartItemIds: [],
          productAmountText: "0.00",
          deliveryFeeText: "0.00",
          packageFeeText: "0.00",
          totalText: "0.00"
        });
        wx.showToast({ title: "请先选择商品", icon: "none" });
        return;
      }
      if (!address) {
        wx.showToast({ title: "请先添加收货地址", icon: "none" });
        wx.navigateTo({ url: "/pages/address/index" });
        return;
      }
      if (!slot) {
        wx.showToast({ title: "暂无可预约配送时间", icon: "none" });
        return;
      }
      const preview = await previewOrder({
        addressId: address.id,
        deliverySlotId: slot.id,
        cartItemIds
      });
      this.setData({
        address: preview.address,
        slots: remoteSlots,
        activeSlotId: slot.id,
        items: preview.items.map((item) => ({
          ...item,
          amountText: yuan(item.amount)
        })),
        cartItemIds,
        productAmountText: yuan(preview.productAmount),
        deliveryFeeText: yuan(preview.deliveryFee),
        packageFeeText: yuan(preview.packageFee),
        totalText: yuan(preview.payableAmount)
      });
      return;
    } catch {
      wx.showToast({ title: "订单信息加载失败", icon: "none" });
    } finally {
      this.setData({ loading: false });
    }
  },

  async chooseSlot(event) {
    const activeSlotId = Number(event.currentTarget.dataset.id);
    this.setData({ activeSlotId });
    if (!this.data.address || !this.data.cartItemIds.length) {
      return;
    }
    try {
      const preview = await previewOrder({
        addressId: this.data.address.id,
        deliverySlotId: activeSlotId,
        cartItemIds: this.data.cartItemIds
      });
      this.setData({
        items: preview.items.map((item) => ({
          ...item,
          amountText: yuan(item.amount)
        })),
        productAmountText: yuan(preview.productAmount),
        deliveryFeeText: yuan(preview.deliveryFee),
        packageFeeText: yuan(preview.packageFee),
        totalText: yuan(preview.payableAmount)
      });
    } catch {}
  },

  async handlePay() {
    if (!requireCompleteProfile("/pages/checkout/index")) {
      return;
    }
    if (!this.data.address || !this.data.activeSlotId || !this.data.cartItemIds.length) {
      wx.showToast({ title: "订单信息不完整", icon: "none" });
      return;
    }
    try {
      const order = await createOrder({
        addressId: this.data.address.id,
        deliverySlotId: this.data.activeSlotId,
        cartItemIds: this.data.cartItemIds,
        remark: ""
      });
      const payment = await payOrder(order.id);
      const paymentResult = await requestWechatPayment(payment);
      if (paymentResult && paymentResult.developmentMode) {
        await confirmDevelopmentPayment(order.id);
      }
      wx.showToast({ title: "支付成功", icon: "success" });
      wx.redirectTo({ url: `/pages/order-detail/index?id=${order.id}` });
    } catch (error) {
      wx.showToast({ title: error.message || "支付失败，请重试", icon: "none" });
    }
  }
});
