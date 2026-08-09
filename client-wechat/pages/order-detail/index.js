const { yuan } = require("../../utils/format");
const { getHome } = require("../../api/catalog");
const { getOrder, payOrder } = require("../../api/orders");
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

function getStatusConfig(order) {
  const status = order ? order.status : "";
  switch (status) {
    case "待支付":
      return {
        title: "等待买家付款",
        desc: "请在规定时间内完成支付，超时订单将自动取消",
        iconPath: "/assets/icons/status-pending.svg",
        themeClass: "is-pending"
      };
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
        desc: "感谢您的支持，欢迎再次光临",
        iconPath: "/assets/icons/status-completed.svg",
        themeClass: "is-completed"
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
        statusConfig: getStatusConfig(order),
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
        statusConfig: getStatusConfig(order),
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
      await requestWechatPayment(payment);
      const order = await waitForPaymentResult(this.data.orderId);
      if (!isPaidOrder(order)) {
        this.setData({ paymentNotice: "支付结果还在确认中，请稍后刷新订单状态。" });
        return;
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
      wx.showModal({
        title: "支付失败",
        content: paymentErrorMessage(error, "支付失败，请稍后重试"),
        showCancel: false
      });
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
