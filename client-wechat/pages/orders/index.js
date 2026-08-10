const { yuan } = require("../../utils/format");
const { getCart } = require("../../api/cart");
const { getHome } = require("../../api/catalog");
const {
  cancelOrder,
  changeToWechatPayment,
  createPaymentShare,
  getOrders,
  getOrder
} = require("../../api/orders");
const { syncTheme } = require("../../utils/theme");
const {
  isPaidOrder,
  isPaymentCancelled,
  paymentErrorMessage,
  requestWechatPayment,
  waitForPaymentResult
} = require("../../utils/wechat-payment");

const DEFAULT_CONTACT_PHONE = "400-800-1234";

const TABS = ["全部", "待支付", "待接单", "备货中", "配送中", "已完成", "售后"];

function isAfterSaleStatus(status) {
  return ["退款中", "部分退款", "已退款", "已拒绝"].includes(status);
}

function primaryActionText(order) {
  if (order.latestRefundStatus === "已拒绝") {
    return "查看拒绝原因";
  }
  if (order.status === "待支付") {
    return "去支付";
  }
  if (order.status === "已关闭") {
    return "查看详情";
  }
  if (isAfterSaleStatus(order.status)) {
    return "查看售后";
  }
  if (order.status === "已完成") {
    return "再来一单";
  }
  if (["备货中", "配送中"].includes(order.status)) {
    return "查看进度";
  }
  if (order.status === "已取消") {
    return "查看详情";
  }
  return "查看详情";
}

function secondaryActionText(order) {
  if (order.status === "待支付") {
    return "取消订单";
  }
  if (["待接单", "备货中", "配送中"].includes(order.status)) {
    return "联系客服";
  }
  if (order.status === "已完成") {
    return "申请售后";
  }
  if (isAfterSaleStatus(order.status)) {
    return "";
  }
  if (order.status === "已取消" || order.status === "已关闭") {
    return "";
  }
  return "";
}

function refundNotice(order) {
  if (order.latestRefundStatus !== "已拒绝") {
    return "";
  }
  return `退款申请未通过：${order.latestRefundReason || "请联系门店客服了解原因"}`;
}

Page({
  data: {
    glassMode: false,
    loading: true,
    tabs: TABS,
    activeStatus: "全部",
    cartCount: 0,
    orders: [],
    payingOrderId: null,
    showCancelModal: false,
    cancelOrderId: null,
    cancelling: false,
    needsLogin: false,
    emptyTitle: "还没有订单",
    emptyDesc: "下单后，配送进度、支付状态和售后记录都会在这里更新。"
  },

  onLoad(options) {
    const status = decodeURIComponent(options.status || "全部");
    this.setData({ activeStatus: TABS.includes(status) ? status : "全部" });
  },

  onShow() {
    syncTheme(this);
    this.updateOrders(this.data.activeStatus);
    this.loadCartCount();
  },

  async loadCartCount() {
    const app = getApp();
    if (!app.isLoggedIn || !app.isLoggedIn()) {
      this.setData({ cartCount: 0 });
      return;
    }
    try {
      const cart = await getCart();
      this.setData({ cartCount: (cart.items || []).length });
    } catch {}
  },

  async updateOrders(status) {
    const app = getApp();
    if (!app.isLoggedIn || !app.isLoggedIn()) {
      this.setData({
        loading: false,
        needsLogin: true,
        orders: [],
        emptyTitle: "登录后查看订单",
        emptyDesc: "登录后会同步你的订单、预约配送时间和售后进度。"
      });
      return;
    }
    try {
      const remoteOrders = await getOrders({ status });
      const detailedOrders = await Promise.all(
        remoteOrders.map(async (item) => {
          try {
            const detail = await getOrder(item.id);
            return { ...item, ...detail };
          } catch {
            return item;
          }
        })
      );

      this.setData({
        loading: false,
        needsLogin: false,
        orders: detailedOrders.map((item) => {
          const itemsList = item.items || [];
          const images = itemsList.length
            ? itemsList.map((i) => i.imageUrl || i.image).filter(Boolean)
            : (item.images || []);

          let calculatedCount = 0;
          if (itemsList.length > 0) {
            calculatedCount = itemsList.reduce((sum, i) => sum + Number(i.quantity || 1), 0);
          } else if (item.summary) {
            const match = item.summary.match(/\d+/);
            if (match) {
              calculatedCount = parseInt(match[0], 10);
            }
          }
          const totalItemCount = calculatedCount || images.length || 1;
          const isSingleProduct = totalItemCount === 1 || (itemsList.length === 1 && Number(itemsList[0].quantity) === 1);
          const firstItemName = itemsList.length ? (itemsList[0].name || itemsList[0].productName) : (item.summary || "");

          let statusClass = "";
          if (item.status === "待支付") statusClass = "is-pending";
          else if (["待接单", "备货中", "配送中"].includes(item.status)) statusClass = "is-active";
          else if (item.status === "已完成") statusClass = "is-completed";
          else if (item.status === "已取消" || item.status === "已关闭") statusClass = "is-canceled";
          else if (isAfterSaleStatus(item.status)) statusClass = "is-refund";

          let primaryBtnClass = "btn-secondary";
          if (item.status === "待支付") primaryBtnClass = "btn-pay";
          else if (["已完成", "待接单", "备货中", "配送中"].includes(item.status)) primaryBtnClass = "btn-primary";

          return {
            ...item,
            images,
            totalText: yuan(item.totalAmount),
            refundNotice: refundNotice(item),
            primaryActionText: primaryActionText(item),
            secondaryActionText: secondaryActionText(item),
            isPendingPayment: item.status === "待支付",
            statusClass,
            primaryBtnClass,
            isSingleProduct,
            singleProductName: firstItemName,
            singleProductSpec: itemsList.length ? (itemsList[0].specificationText || "") : "",
            singleProductQuantity: itemsList.length ? itemsList[0].quantity : 1,
            totalItemCount
          };
        }),
        emptyTitle: status === "全部" ? "还没有下过单" : `暂无${status}订单`,
        emptyDesc: status === "全部"
          ? "去首页挑选一些新鲜食材，提交订单后这里会自动记录。"
          : "当前状态下没有订单，切换其它状态或去首页下单看看。"
      });
    } catch (error) {
      this.setData({
        loading: false,
        needsLogin: error && error.loginRequired,
        orders: [],
        emptyTitle: error && error.loginRequired ? "登录后查看订单" : "订单暂时加载失败",
        emptyDesc: error && error.loginRequired
          ? "登录后会同步你的订单、预约配送时间和售后进度。"
          : "网络或服务暂时不可用，稍后再试。"
      });
      if (!error || !error.loginRequired) {
        wx.showToast({ title: "订单加载失败", icon: "none" });
      }
    }
  },

  handleTab(event) {
    const status = event.currentTarget.dataset.status;
    this.setData({ activeStatus: status });
    this.updateOrders(status);
  },

  goDetail(event) {
    const id = event.currentTarget.dataset.id;
    wx.navigateTo({ url: `/pages/order-detail/index?id=${id}` });
  },

  handleOrderAction(event) {
    const id = event.currentTarget.dataset.id;
    wx.navigateTo({ url: `/pages/order-detail/index?id=${id}` });
  },

  async handlePendingAction(event) {
    const id = Number(event.currentTarget.dataset.id);
    const action = event.currentTarget.dataset.action;
    if (!id || this.data.payingOrderId) return;

    if (action === "cancel") {
      this.setData({ showCancelModal: true, cancelOrderId: id });
      return;
    }
    if (action === "service") {
      await this.handleService();
      return;
    }
    if (action === "friend-pay") {
      await this.handleFriendPayment(id);
      return;
    }
    if (action === "wechat-pay") {
      await this.handleWechatPayment(id);
    }
  },

  async handleFriendPayment(id) {
    this.setData({ payingOrderId: id });
    try {
      const share = await createPaymentShare(id);
      if (!share || !share.token) {
        throw new Error("支付链接生成失败，请稍后重试");
      }
      wx.navigateTo({
        url: `/pages/pay-for-other/index?token=${encodeURIComponent(share.token)}&owner=1&orderId=${id}`
      });
    } catch (error) {
      wx.showToast({ title: error.message || "支付链接生成失败", icon: "none" });
    } finally {
      this.setData({ payingOrderId: null });
    }
  },

  async handleWechatPayment(id) {
    this.setData({ payingOrderId: id });
    try {
      const payment = await changeToWechatPayment(id);
      await requestWechatPayment(payment);
      const order = await waitForPaymentResult(id);
      if (!isPaidOrder(order)) {
        wx.showModal({
          title: "支付处理中",
          content: "微信已返回支付结果，订单状态还在确认中，请稍后刷新订单。",
          showCancel: false
        });
        return;
      }
      wx.showToast({ title: "支付成功", icon: "success" });
      await this.updateOrders(this.data.activeStatus);
    } catch (error) {
      if (isPaymentCancelled(error)) {
        wx.showToast({ title: "支付已取消", icon: "none" });
        return;
      }
      wx.showModal({
        title: "支付失败",
        content: paymentErrorMessage(error, "支付失败，请稍后重试"),
        showCancel: false
      });
      await this.updateOrders(this.data.activeStatus);
    } finally {
      this.setData({ payingOrderId: null });
    }
  },

  async handleService() {
    let phone = DEFAULT_CONTACT_PHONE;
    try {
      const home = await getHome();
      phone = home.contactPhone || phone;
    } catch {}
    wx.showModal({
      title: "联系客服",
      content: `禺邻优鲜客服电话：${phone}`,
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
  },

  async handleSecondaryAction(event) {
    const id = event.currentTarget.dataset.id;
    const order = this.data.orders.find((item) => Number(item.id) === Number(id));
    if (order && String(order.status || "").trim() === "待支付") {
      this.setData({ showCancelModal: true, cancelOrderId: id });
      return;
    }
    wx.navigateTo({ url: `/pages/order-detail/index?id=${id}` });
  },

  stopCancelModalTap() {},

  closeCancelModal() {
    if (!this.data.cancelling) {
      this.setData({ showCancelModal: false, cancelOrderId: null });
    }
  },

  async handleCancelChoice(event) {
    if (this.data.cancelling) return;
    const value = event.currentTarget.dataset.returnToCart;
    const returnToCart = value === true || value === "true";
    const id = this.data.cancelOrderId;
    if (!id) return;
    this.setData({ cancelling: true, showCancelModal: false });
    try {
      await cancelOrder(id, returnToCart);
      wx.showToast({
        title: returnToCart ? "已取消并放回购物车" : "订单已取消",
        icon: "success"
      });
      await Promise.all([
        this.updateOrders(this.data.activeStatus),
        this.loadCartCount()
      ]);
    } catch (error) {
      wx.showToast({ title: error.message || "取消订单失败", icon: "none" });
      await this.updateOrders(this.data.activeStatus);
    } finally {
      this.setData({ cancelling: false, showCancelModal: false, cancelOrderId: null });
    }
  },

  goHome() {
    wx.redirectTo({ url: "/pages/home/index" });
  },

  goLogin() {
    wx.navigateTo({ url: "/pages/login/index?redirect=%2Fpages%2Forders%2Findex" });
  }
});
