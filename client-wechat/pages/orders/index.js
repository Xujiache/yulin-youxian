const { yuan } = require("../../utils/format");
const { getCart } = require("../../api/cart");
const { cancelOrder, getOrders, getOrder } = require("../../api/orders");
const { syncTheme } = require("../../utils/theme");

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
    return "重启支付";
  }
  if (isAfterSaleStatus(order.status)) {
    return "查看售后";
  }
  if (order.status === "已完成") {
    return "申请售后";
  }
  if (["备货中", "配送中"].includes(order.status)) {
    return "查看进度";
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
  if (isAfterSaleStatus(order.status)) {
    return "";
  }
  if (order.status === "已完成") {
    return "再来一单";
  }
  if (order.status === "已取消") {
    return "查看详情";
  }
  if (order.status === "已关闭") {
    return "查看详情";
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

          return {
            ...item,
            images,
            totalText: yuan(item.totalAmount),
            refundNotice: refundNotice(item),
            primaryActionText: primaryActionText(item),
            secondaryActionText: secondaryActionText(item),
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
