const { yuan } = require("../../utils/format");
const { getCart } = require("../../api/cart");
const { getHome } = require("../../api/catalog");
const { getDeliverySlots } = require("../../api/delivery");
const {
  cancelOrder,
  getOrders,
  getOrder,
  restartOrder
} = require("../../api/orders");
const { mapWithConcurrency } = require("../../utils/async-pool");
const { syncTheme } = require("../../utils/theme");
const { isPaymentCancelled } = require("../../utils/wechat-payment");

const DEFAULT_CONTACT_PHONE = "400-800-1234";
const ORDER_DETAIL_CONCURRENCY = 4;
const ORDER_DETAIL_CACHE_TTL = 30000;
const orderDetailCache = new Map();

const TABS = ["全部", "待支付", "待接单", "备货中", "配送中", "已完成", "售后"];

function isAfterSaleStatus(status) {
  return ["退款中", "部分退款", "已退款", "已拒绝"].includes(status);
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

function isActiveOrderStatus(status) {
  return ["已支付/待接单", "待接单", "备货中", "配送中"].includes(status);
}

function displayOrderStatus(status) {
  return status === "已支付/待接单" ? "待接单" : status;
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
    return "查看详情";
  }
  if (isActiveOrderStatus(order.status)) {
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
  if (isActiveOrderStatus(order.status)) {
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

function readCachedOrderDetail(id) {
  const key = String(id);
  const cached = orderDetailCache.get(key);
  if (!cached) {
    return null;
  }
  if (Date.now() - cached.savedAt > ORDER_DETAIL_CACHE_TTL) {
    orderDetailCache.delete(key);
    return null;
  }
  return cached.order;
}

function cacheOrderDetail(id, order) {
  orderDetailCache.set(String(id), {
    order,
    savedAt: Date.now()
  });
}

function invalidateOrderDetail(id) {
  orderDetailCache.delete(String(id));
}

function mergeOrderDetail(summary, detail) {
  if (!detail) {
    return summary;
  }
  return {
    ...detail,
    ...summary,
    items: Array.isArray(detail.items) && detail.items.length ? detail.items : (summary.items || []),
    refunds: Array.isArray(detail.refunds) ? detail.refunds : (summary.refunds || [])
  };
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
    this._ordersRequestToken = 0;
    const status = decodeURIComponent(options.status || "全部");
    this.setData({ activeStatus: TABS.includes(status) ? status : "全部" });
  },

  onShow() {
    syncTheme(this);
    this.updateOrders(this.data.activeStatus);
    this.loadCartCount();
  },

  onUnload() {
    this._ordersRequestToken += 1;
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
    const requestToken = this._ordersRequestToken + 1;
    this._ordersRequestToken = requestToken;
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
    this.setData({ loading: true });
    try {
      const remoteOrders = await getOrders({ status });
      const detailedOrders = await mapWithConcurrency(
        remoteOrders,
        ORDER_DETAIL_CONCURRENCY,
        async (item) => {
          const cached = readCachedOrderDetail(item.id);
          if (cached) {
            return mergeOrderDetail(item, cached);
          }
          try {
            const detail = await getOrder(item.id);
            cacheOrderDetail(item.id, detail);
            return mergeOrderDetail(item, detail);
          } catch {
            return item;
          }
        }
      );
      if (requestToken !== this._ordersRequestToken) {
        return;
      }

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
          else if (isActiveOrderStatus(item.status)) statusClass = "is-active";
          else if (item.status === "已完成") statusClass = "is-completed";
          else if (item.status === "已取消" || item.status === "已关闭") statusClass = "is-canceled";
          else if (isAfterSaleStatus(item.status)) statusClass = "is-refund";

          let primaryBtnClass = "btn-secondary";
          if (item.status === "待支付") primaryBtnClass = "btn-pay";
          else if (item.status === "已完成" || isActiveOrderStatus(item.status)) primaryBtnClass = "btn-primary";

          return {
            ...item,
            images,
            totalText: yuan(item.totalAmount),
            refundNotice: refundNotice(item),
            primaryActionText: primaryActionText(item),
            secondaryActionText: secondaryActionText(item),
            statusText: displayOrderStatus(item.status),
            isPendingPayment: item.status === "待支付",
            canViewDelivery: item.status === "配送中",
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
      if (requestToken !== this._ordersRequestToken) {
        return;
      }
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

  // 配送中订单直达详情页的配送卡片
  handleViewDelivery(event) {
    const id = event.currentTarget.dataset.id;
    if (!id) return;
    wx.navigateTo({ url: `/pages/order-detail/index?id=${id}&focus=delivery` });
  },

  async handleRestartableAction(event) {
    const id = Number(event.currentTarget.dataset.id);
    const action = event.currentTarget.dataset.action;
    if (!id || this.data.payingOrderId) return;
    if (action === "detail") {
      wx.navigateTo({ url: `/pages/order-detail/index?id=${id}` });
      return;
    }
    if (action === "service") {
      await this.handleService();
      return;
    }
    if (action !== "restart") return;

    const order = this.data.orders.find((item) => Number(item.id) === id);
    this.setData({ payingOrderId: id });
    try {
      let deliverySlotId = null;
      if (order && order.requiresDeliverySlotSelection) {
        const slots = (await getDeliverySlots()).filter((slot) => slot && slot.available !== false).slice(0, 6);
        if (!slots.length) {
          wx.showToast({ title: "暂无可选配送时间", icon: "none" });
          return;
        }
        const selectedSlot = await chooseDeliverySlot(slots);
        if (!selectedSlot) return;
        deliverySlotId = selectedSlot.id;
      }
      await restartOrder(id, deliverySlotId);
      invalidateOrderDetail(id);
      await this.updateOrders(this.data.activeStatus);
      wx.showToast({ title: "订单已重启", icon: "success" });
      wx.navigateTo({ url: `/pages/order-detail/index?id=${id}` });
    } catch (error) {
      wx.showToast({ title: error.message || "重启支付失败，请稍后重试", icon: "none" });
      await this.updateOrders(this.data.activeStatus);
    } finally {
      this.setData({ payingOrderId: null });
    }
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
    wx.navigateTo({
      url: `/pages/order-detail/index?id=${id}&payMethod=FRIEND`
    });
  },

  async handleWechatPayment(id) {
    wx.navigateTo({
      url: `/pages/order-detail/index?id=${id}&payMethod=WECHAT`
    });
  },

  async handleService() {
    let phone = DEFAULT_CONTACT_PHONE;
    try {
      const home = await getHome();
      phone = home.contactPhone || phone;
    } catch {}
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
  },

  async handleSecondaryAction(event) {
    const id = event.currentTarget.dataset.id;
    const order = this.data.orders.find((item) => Number(item.id) === Number(id));
    if (order && String(order.status || "").trim() === "待支付") {
      this.setData({ showCancelModal: true, cancelOrderId: id });
      return;
    }
    if (order && isActiveOrderStatus(order.status)) {
      await this.handleService();
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
      invalidateOrderDetail(id);
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
