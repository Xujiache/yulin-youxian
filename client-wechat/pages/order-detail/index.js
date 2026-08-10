const { yuan } = require("../../utils/format");
const { getHome } = require("../../api/catalog");
const { getDeliverySlots } = require("../../api/delivery");
const { cancelOrder, getOrder, payOrder, restartOrder } = require("../../api/orders");
const { syncTheme } = require("../../utils/theme");
const {
  isPaidOrder,
  isPaymentCancelled,
  isPendingPaymentOrder,
  paymentErrorMessage,
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

function paymentDeadlineText(value) {
  if (!value) return "";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "";
  const pad = (number) => String(number).padStart(2, "0");
  return `${date.getMonth() + 1}月${date.getDate()}日 ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

function getStatusConfig(order) {
  const status = order ? order.status : "";
  switch (status) {
    case "待支付": {
      const deadline = paymentDeadlineText(order.paymentExpireAt);
      return {
        title: "等待买家付款",
        desc: deadline ? `请在 ${deadline} 前完成支付，超时订单将自动关闭` : "请在 6 小时内完成支付，超时订单将自动关闭",
        iconPath: "/assets/icons/status-pending.svg",
        themeClass: "is-pending"
      };
    }
    case "已支付/待接单":
    case "待接单":
      return {
        title: "订单已提交，等待商家接单",
        desc: "商家正在确认订单，请耐心等待",
        iconPath: "/assets/icons/status-paid.svg",
        themeClass: "is-paid"
      };
    case "备货中":
      return {
        title: "商家正在备货中",
        desc: "商品正在挑选拣货，新鲜即将送达",
        iconPath: "/assets/icons/status-preparing.svg",
        themeClass: "is-preparing"
      };
    case "配送中":
      return {
        title: "商品配送中",
        desc: "骑手正在火速配送，请保持电话畅通",
        iconPath: "/assets/icons/status-delivering.svg",
        themeClass: "is-delivering"
      };
    case "已完成":
      return {
        title: "订单已完成",
        desc: "感谢你的支持，欢迎再次光临",
        iconPath: "/assets/icons/status-completed.svg",
        themeClass: "is-completed"
      };
    case "已关闭":
      return {
        title: "订单已超时关闭",
        desc: order.requiresDeliverySlotSelection ? "可重启支付，重新选择配送时间后继续下单" : "可重启订单并继续完成支付",
        iconPath: "/assets/icons/status-cancelled.svg",
        themeClass: "is-closed"
      };
    case "已取消":
      return {
        title: "订单已取消",
        desc: "如有需要可以重新下单购买",
        iconPath: "/assets/icons/status-cancelled.svg",
        themeClass: "is-cancelled"
      };
    default:
      if (["退款中", "部分退款", "已退款"].includes(status)) {
        return {
          title: status,
          desc: "退款进度更新中，请查看售后详情",
          iconPath: "/assets/icons/status-refund.svg",
          themeClass: "is-refund"
        };
      }
      return {
        title: status || "订单处理中",
        desc: "订单详情更新中",
        iconPath: "/assets/icons/status-preparing.svg",
        themeClass: "is-default"
      };
  }
}

function canApplyRefund(order) {
  if (!order || Number(order.paidAmount || 0) <= Number(order.refundedAmount || 0)) {
    return false;
  }
  return !["待支付", "已关闭", "已取消", "已退款"].includes(order.status);
}

function chooseDeliverySlot(slots) {
  return new Promise((resolve, reject) => {
    wx.showActionSheet({
      itemList: slots.map((slot) => slot.label),
      success(result) {
        resolve(slots[result.tapIndex] || null);
      },
      fail(error) {
        if (isPaymentCancelled(error)) {
          resolve(null);
          return;
        }
        reject(error);
      }
    });
  });
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
    statusConfig: {},
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
    canCancel: false,
    canRestartPayment: false,
    canRefund: false,
    paying: false,
    restarting: false,
    cancelling: false,
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
      this.applyOrder(order);
    } catch {
      wx.showToast({ title: "订单详情加载失败", icon: "none" });
    } finally {
      this.setData({ loading: false });
    }
  },

  onShow() {
    syncTheme(this);
    if (this.data.orderId && !this.data.loading && !this.data.paying && !this.data.restarting) {
      this.refreshOrder();
    }
  },

  applyOrder(order) {
    this.setData({
      order,
      orderId: order.id,
      orderNo: order.orderNo,
      statusText: order.status,
      statusConfig: getStatusConfig(order),
      isPendingPayment: isPendingPaymentOrder(order),
      canCancel: isPendingPaymentOrder(order),
      canRestartPayment: Boolean(order.canRestartPayment),
      canRefund: canApplyRefund(order),
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
  },

  async refreshOrder() {
    try {
      const order = await getOrder(this.data.orderId);
      this.applyOrder(order);
      this.setData({ paymentNotice: "" });
    } catch {}
  },

  async handlePay() {
    if (!this.data.orderId || this.data.paying || !this.data.isPendingPayment) {
      return;
    }
    this.setData({ paying: true, paymentNotice: "" });
    try {
      const payment = await payOrder(this.data.orderId);
      await requestWechatPayment(payment);
      const order = await waitForPaymentResult(this.data.orderId);
      if (!isPaidOrder(order)) {
        this.setData({ paymentNotice: "支付结果还在确认中，请稍后刷新订单状态。" });
        return;
      }
      this.applyOrder(order);
      wx.showToast({ title: "支付成功", icon: "success" });
    } catch (error) {
      if (this.data.orderId && !isPaymentCancelled(error)) {
        try {
          const order = await waitForPaymentResult(this.data.orderId, { attempts: 3, interval: 700 });
          if (isPaidOrder(order)) {
            this.applyOrder(order);
            wx.showToast({ title: "支付成功", icon: "success" });
            return;
          }
        } catch {}
      }
      if (isPaymentCancelled(error)) {
        this.setData({ paymentNotice: "支付已取消，订单在 6 小时有效期内仍可继续支付。" });
        return;
      }
      wx.showModal({
        title: "支付失败",
        content: paymentErrorMessage(error, "支付失败，请稍后重试"),
        showCancel: false
      });
    } finally {
      this.setData({ paying: false });
    }
  },

  async handleRestartPayment() {
    const order = this.data.order;
    if (!order || !this.data.canRestartPayment || this.data.restarting || this.data.paying) {
      return;
    }
    this.setData({ restarting: true, paymentNotice: "" });
    try {
      let deliverySlotId = null;
      if (order.requiresDeliverySlotSelection) {
        const slots = (await getDeliverySlots()).filter((slot) => slot && slot.available !== false).slice(0, 6);
        if (!slots.length) {
          wx.showToast({ title: "暂无可选配送时间", icon: "none" });
          return;
        }
        const selectedSlot = await chooseDeliverySlot(slots);
        if (!selectedSlot) {
          return;
        }
        deliverySlotId = selectedSlot.id;
      }
      const restarted = await restartOrder(order.id, deliverySlotId);
      this.applyOrder(restarted);
      this.setData({ restarting: false });
      await this.handlePay();
    } catch (error) {
      wx.showModal({
        title: "重启支付失败",
        content: error.message || "订单暂时无法重启，请稍后重试",
        showCancel: false
      });
    } finally {
      this.setData({ restarting: false });
    }
  },

  async handleCancelOrder() {
    if (!this.data.canCancel || this.data.cancelling) {
      return;
    }
    const choice = await new Promise((resolve) => {
      wx.showModal({
        title: "取消订单",
        content: "取消后，是否将本单商品放回购物车？",
        confirmText: "放回购物车",
        cancelText: "不要了",
        confirmColor: "#008a52",
        success: resolve,
        fail: () => resolve(null)
      });
    });
    if (!choice) return;
    const returnToCart = Boolean(choice.confirm);
    this.setData({ cancelling: true });
    try {
      const cancelled = await cancelOrder(this.data.orderId, returnToCart);
      this.applyOrder(cancelled);
      wx.showToast({
        title: returnToCart ? "已取消并放回购物车" : "订单已取消",
        icon: "success"
      });
    } catch (error) {
      wx.showToast({ title: error.message || "取消订单失败", icon: "none" });
      await this.refreshOrder();
    } finally {
      this.setData({ cancelling: false });
    }
  },

  async loadContactPhone() {
    try {
      const home = await getHome();
      this.setData({ contactPhone: home.contactPhone || DEFAULT_CONTACT_PHONE });
    } catch {}
  },

  copyOrderNo() {
    if (!this.data.orderNo) return;
    wx.setClipboardData({
      data: this.data.orderNo,
      success() {
        wx.showToast({ title: "已复制订单号", icon: "success" });
      }
    });
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
        if (!result.confirm) return;
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
