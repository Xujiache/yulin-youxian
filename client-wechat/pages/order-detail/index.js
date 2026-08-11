const { yuan } = require("../../utils/format");
const { getHome } = require("../../api/catalog");
const {
  getDeliverySlots,
  getTracking,
  subscribeDelivery,
  submitRating
} = require("../../api/delivery");
const {
  cancelOrder,
  changeToWechatPayment,
  createPaymentShare,
  getOrder,
  getPaymentMethod,
  restartOrder
} = require("../../api/orders");
const {
  createLotteryChallenge,
  drawLottery,
  getOrderLottery
} = require("../../api/marketing");
const { syncTheme } = require("../../utils/theme");
const {
  EMPTY_DELIVERY,
  RATING_TAGS,
  markerAnimationDuration,
  normalizeTracking,
  shouldContinueUnavailableTracking,
  trackingPollDelayMs
} = require("./tracking");
const {
  clearLotterySession,
  readLotterySession,
  writeLotterySession
} = require("../../utils/lottery-session");
const {
  isPaidOrder,
  isPaymentCancelled,
  isPendingPaymentOrder,
  paymentErrorMessage,
  requestWechatPayment,
  waitForPaymentResult
} = require("../../utils/wechat-payment");

const DEFAULT_CONTACT_PHONE = "400-800-1234";
const DELIVERING_STATUS = "配送中";
const COMPLETED_STATUS = "已完成";
const TRACKING_INTERVAL = 5000;
const TRACKING_BACKOFF_INTERVAL = 15000;
const TRACKING_FAILURE_LIMIT = 3;
// 略小于轮询间隔，避免上一次动画没跑完就被下一次打断
const RIDER_MOVE_DURATION = 4800;
const MAP_ID = "deliveryMap";
const MARKER_STORE = 1;
const MARKER_RIDER = 2;
const MARKER_DESTINATION = 3;
const MAP_EXPANDED_KEY = "deliveryMapExpanded";
// 微信 map 组件的 marker 在真机上不支持 svg，必须用位图
const ICON_STORE = "/assets/delivery/marker-store.png";
const ICON_RIDER = "/assets/delivery/marker-rider.png";
const ICON_RIDER_STALE = "/assets/delivery/marker-rider-stale.png";
const ICON_DESTINATION = "/assets/delivery/marker-destination.png";
const RATING_STARS = [1, 2, 3, 4, 5];
const PAYMENT_METHODS = [
  {
    code: "WECHAT",
    title: "微信支付",
    desc: "使用当前微信账号完成付款",
    icon: "/assets/payment/wechat-pay.png"
  },
  {
    code: "FRIEND",
    title: "请好友付款",
    desc: "生成代付链接并分享给好友",
    icon: "/assets/payment/friend-pay.png"
  }
];

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
  return !["待支付", "已关闭", "已取消", "退款中", "已退款"].includes(order.status);
}

function isCancelableOrder(order) {
  return String(order && order.status || "").trim() === "待支付";
}

function lotteryCampaignEnabled(state) {
  return Boolean(state && state.campaign && state.campaign.enabled !== false);
}

function lotteryReasonText(state) {
  const reason = String((state && state.reason) || "");
  const messages = {
    CAMPAIGN_DISABLED: "活动已结束",
    CAMPAIGN_NOT_STARTED: "活动尚未开始",
    ORDER_NOT_ELIGIBLE: "本单不符合活动条件",
    BELOW_THRESHOLD: "本单未达到活动门槛",
    CHALLENGE_EXPIRED: "分享凭证已失效",
    ORDER_NOT_PENDING: "订单状态已变化"
  };
  return messages[reason] || reason || "本单暂不能参与鲜礼活动";
}

function lotteryChallengeInvalid(state) {
  return [
    "CHALLENGE_EXPIRED",
    "CHALLENGE_INVALID",
    "INVALID_CHALLENGE"
  ].includes(String((state && state.reason) || ""));
}

function modalChoice(options) {
  return new Promise((resolve) => {
    wx.showModal({
      ...options,
      success: (result) => resolve(Boolean(result.confirm)),
      fail: () => resolve(false)
    });
  });
}

function buildMarkers(delivery, riderPoint) {
  const markers = [];
  if (delivery.store) {
    markers.push({
      id: MARKER_STORE,
      latitude: delivery.store.lat,
      longitude: delivery.store.lng,
      iconPath: ICON_STORE,
      width: 32,
      height: 40,
      anchor: { x: 0.5, y: 1 },
      zIndex: 1
    });
  }
  if (delivery.destination) {
    markers.push({
      id: MARKER_DESTINATION,
      latitude: delivery.destination.lat,
      longitude: delivery.destination.lng,
      iconPath: ICON_DESTINATION,
      width: 32,
      height: 40,
      anchor: { x: 0.5, y: 1 },
      zIndex: 2
    });
  }
  if (riderPoint && delivery.rider) {
    markers.push({
      id: MARKER_RIDER,
      latitude: riderPoint.lat,
      longitude: riderPoint.lng,
      iconPath: delivery.rider.fresh ? ICON_RIDER : ICON_RIDER_STALE,
      width: 34,
      height: 34,
      anchor: { x: 0.5, y: 0.5 },
      rotate: delivery.rider.bearing,
      zIndex: 3
    });
  }
  return markers;
}

// 顾客侧不返回历史轨迹，只画骑手到目的地的一段虚线，点数很少不会掉帧
function buildPolyline(from, to) {
  if (!from || !to) {
    return [];
  }
  return [
    {
      points: [
        { latitude: from.lat, longitude: from.lng },
        { latitude: to.lat, longitude: to.lng }
      ],
      color: "#27AE60AA",
      width: 5,
      dottedLine: true
    }
  ];
}

// WXML 表达式不支持 indexOf，选中态在 js 侧算好
function buildRatingTagList() {
  return RATING_TAGS.map((tag) => ({ tag, selected: false }));
}

function subscribeErrorText(error) {
  const code = error && (error.errCode || error.errno);
  if (code === 20004) {
    return "你关闭了订阅消息总开关，请在微信「设置-订阅消息」中开启后重试";
  }
  return "暂时无法开启提醒，请稍后重试";
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
    gifts: [],
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
    lotteryDiscountText: "0.00",
    hasLotteryDiscount: false,
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
    showCancelModal: false,
    showPaymentMethodModal: false,
    paymentMethodLoading: false,
    paymentMethodSubmitting: false,
    paymentMethods: PAYMENT_METHODS,
    paymentMethod: "WECHAT",
    flowPaymentMethod: "WECHAT",
    selectedPaymentMethod: "WECHAT",
    paymentNotice: "",
    lotteryState: null,
    lotteryBusy: false,
    showLotteryChoice: false,
    showLuckyWheel: false,
    wheelPrizes: [],
    wheelInitialResult: null,
    lotteryResult: null,
    drawLoading: false,
    lotteryContinueLoading: false,
    wheelContinueText: "按最终金额微信支付",
    delivery: { ...EMPTY_DELIVERY },
    mapScale: 15,
    markers: [],
    polyline: [],
    mapExpanded: true,
    mapViewportSafe: false,
    canSubscribe: false,
    subscribing: false,
    canRate: false,
    showRatingSheet: false,
    ratingTagList: buildRatingTagList(),
    ratingStars: RATING_STARS,
    ratingStar: 5,
    ratingComment: "",
    ratingSubmitting: false
  },

  async onLoad(options) {
    syncTheme(this);
    this._pageVisible = true;
    this._trackingActive = false;
    this._trackingTimer = null;
    this._trackingToken = 0;
    this._trackingFailures = 0;
    this._trackingIntervalMs = TRACKING_INTERVAL;
    this._riderCommitted = null;
    this._riderAnimationActive = false;
    this._riderAnimationSequence = 0;
    this._pendingRiderDelivery = null;
    this._mapFitted = false;
    this._refitTimer = null;
    this._mapViewportTimer = null;
    this._completedTrackingLoaded = false;
    this._subscribeAccepted = false;
    this._focusDelivery = options.focus === "delivery";
    this._resumingLottery = false;
    this._requestedPaymentMethod = ["FRIEND", "WECHAT"].includes(options.payMethod)
      ? options.payMethod
      : "";
    this._requestedPaymentStarted = false;
    this.restoreMapExpanded();
    this.loadContactPhone();
    const id = Number(options.id || 0);
    if (!id) {
      this.setData({ loading: false });
      wx.showToast({ title: "订单不存在", icon: "none" });
      return;
    }
    this.setData({ orderId: id });
    try {
      const order = await getOrder(id);
      this.applyOrder(order);
    } catch {
      wx.showToast({ title: "订单详情加载失败", icon: "none" });
    } finally {
      this.setData({ loading: false });
    }
    this.startRequestedPayment();
    this.restoreSavedLottery();
  },

  onShow() {
    syncTheme(this);
    this._pageVisible = true;
    this.resumeLotterySession();
    if (this.data.orderId && !this.data.loading && !this.data.paying && !this.data.restarting) {
      this.refreshOrder();
    }
    this.syncDeliverySection();
  },

  onPageScroll() {
    this.scheduleMapViewportCheck();
  },

  // 切后台必须停：小程序在后台未完成的请求可能直接 fail interrupted
  onHide() {
    this._pageVisible = false;
    this.stopTracking();
    this.clearMapViewportTimer();
  },

  onUnload() {
    this._pageVisible = false;
    this.stopTracking();
    this.clearRefitTimer();
    this.clearMapViewportTimer();
  },

  applyOrder(order) {
    const isPendingPayment = isPendingPaymentOrder(order);
    this.setData({
      order,
      orderId: order.id,
      orderNo: order.orderNo,
      statusText: order.status,
      statusConfig: getStatusConfig(order),
      isPendingPayment,
      canCancel: isCancelableOrder(order),
      canRestartPayment: Boolean(order.canRestartPayment),
      canRefund: canApplyRefund(order),
      address: order.address || {},
      deliverySlotText: order.deliverySlot || "",
      items: (order.items || []).map((item) => ({
        ...item,
        amountText: yuan(item.amount)
      })),
      gifts: (order.gifts || []).map((gift) => ({
        ...gift,
        quantityText: Number(gift.quantity || 1),
        statusText: gift.status === "FULFILLED" ? "已随单送达" : "随单配送"
      })),
      productAmountText: yuan(order.productAmount),
      deliveryFeeText: yuan(order.deliveryFee),
      packageFeeText: yuan(order.packageFee),
      payableText: yuan(order.payableAmount),
      lotteryDiscountText: yuan(order.discountAmount),
      hasLotteryDiscount: Number(order.discountAmount || 0) > 0,
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
    if (isPendingPayment) {
      this.refreshPaymentMethod();
      this.resumeLotterySession();
    }
    this.syncDeliverySection();
  },

  startRequestedPayment() {
    const paymentMethod = this._requestedPaymentMethod;
    if (
      this._requestedPaymentStarted
      || !paymentMethod
      || !this.data.isPendingPayment
      || !this.data.orderId
    ) {
      return;
    }
    this._requestedPaymentStarted = true;
    this.setData({
      flowPaymentMethod: paymentMethod,
      selectedPaymentMethod: paymentMethod
    });
    this.continuePendingOrder(paymentMethod);
  },

  async restoreSavedLottery() {
    if (
      this._requestedPaymentStarted
      || this._resumingLottery
      || !this.data.isPendingPayment
      || !this.data.orderId
      || this.data.showLuckyWheel
      || this.data.showLotteryChoice
    ) {
      return;
    }
    try {
      const state = await getOrderLottery(this.data.orderId);
      if (!state.drawn && !state.shareTriggered) {
        return;
      }
      let paymentMethod = this.data.paymentMethod || "WECHAT";
      try {
        const method = await getPaymentMethod(this.data.orderId);
        paymentMethod = method && method.code === "FRIEND" ? "FRIEND" : "WECHAT";
      } catch {}
      this.setData({
        paymentMethod,
        flowPaymentMethod: paymentMethod,
        selectedPaymentMethod: paymentMethod
      });
      this.applyLotteryGate(state, paymentMethod);
    } catch {}
  },

  async refreshPaymentMethod() {
    if (!this.data.orderId || !this.data.isPendingPayment) return;
    try {
      const method = await getPaymentMethod(this.data.orderId);
      const paymentMethod = method && method.code === "FRIEND" ? "FRIEND" : "WECHAT";
      this.setData({ paymentMethod });
    } catch {}
  },

  async refreshOrder() {
    try {
      const order = await getOrder(this.data.orderId);
      this.applyOrder(order);
      this.setData({ paymentNotice: "" });
    } catch {}
  },

  /* ==================== 配送可视化（docs/rider/08-小程序配送可视化.md） ==================== */

  // 配送中才轮询；已完成只拉一次拿时间轴与评价状态；其它状态直接清空配送卡片
  syncDeliverySection() {
    const status = String((this.data.order && this.data.order.status) || "").trim();
    if (status === DELIVERING_STATUS) {
      this.startTracking();
      return;
    }
    this.stopTracking();
    if (status === COMPLETED_STATUS) {
      this.loadTrackingOnce();
      return;
    }
    this.resetDelivery();
  },

  startTracking() {
    if (!this.data.orderId || this._pageVisible === false || this._trackingActive) {
      return;
    }
    this._trackingActive = true;
    this._trackingToken += 1;
    this._trackingFailures = 0;
    this._trackingIntervalMs = TRACKING_INTERVAL;
    const token = this._trackingToken;
    this.fetchTracking(token);
  },

  stopTracking() {
    // 递增 token 让在途请求的回调直接作废，避免切后台后回来出现旧数据覆盖
    this._trackingActive = false;
    this._trackingToken += 1;
    if (this._trackingTimer) {
      clearTimeout(this._trackingTimer);
      this._trackingTimer = null;
    }
    this._trackingFailures = 0;
    this._trackingIntervalMs = TRACKING_INTERVAL;
    this._riderCommitted = null;
    this.cancelRiderAnimation();
    this._mapFitted = false;
  },

  scheduleTracking(token, intervalMs) {
    if (
      token !== this._trackingToken
      || !this._trackingActive
      || this._pageVisible === false
    ) {
      return;
    }
    if (this._trackingTimer) {
      clearTimeout(this._trackingTimer);
    }
    this._trackingIntervalMs = intervalMs;
    this._trackingTimer = setTimeout(() => {
      this._trackingTimer = null;
      this.fetchTracking(token);
    }, intervalMs);
  },

  fetchTracking(token) {
    const orderId = this.data.orderId;
    if (!orderId) {
      return;
    }
    getTracking(orderId).then(
      (raw) => {
        if (token !== this._trackingToken || !this._trackingActive) {
          return;
        }
        this._trackingFailures = 0;
        this._trackingIntervalMs = TRACKING_INTERVAL;
        this.applyTracking(raw);
        this.scheduleTracking(token, this._trackingIntervalMs);
      },
      () => {
        // 静默失败：保留上一次数据，不弹错误打扰顾客
        if (token !== this._trackingToken) {
          return;
        }
        this._trackingFailures += 1;
        const retryInterval = this._trackingFailures >= TRACKING_FAILURE_LIMIT
          ? Math.max(this._trackingIntervalMs, TRACKING_BACKOFF_INTERVAL)
          : this._trackingIntervalMs;
        this.scheduleTracking(token, retryInterval);
      }
    );
  },

  async loadTrackingOnce() {
    if (!this.data.orderId || this._completedTrackingLoaded) {
      return;
    }
    this._completedTrackingLoaded = true;
    try {
      const raw = await getTracking(this.data.orderId);
      this.applyTracking(raw);
    } catch {
      this._completedTrackingLoaded = false;
    }
  },

  applyTracking(raw) {
    const address = this.data.address || {};
    const delivery = normalizeTracking(raw, { lat: address.latitude, lng: address.longitude });
    const status = String((this.data.order && this.data.order.status) || "").trim();
    const backendInterval = trackingPollDelayMs(
      status,
      raw,
      TRACKING_INTERVAL,
      TRACKING_BACKOFF_INTERVAL
    );
    this._trackingIntervalMs = backendInterval;
    if (!delivery.available) {
      if (shouldContinueUnavailableTracking(status, raw)) {
        // 订单状态桥可能先进入「配送中」，配送任务随后才可见。静态回退，但继续低频探测。
        this.resetDelivery();
        return;
      }
      // 老订单未启用配送域：完全回退到原有静态文案。
      this.stopTracking();
      this.resetDelivery();
      return;
    }
    // 后端记录成功后才保持开启态，避免 tracking 写入延迟导致按钮回跳。
    if (this._subscribeAccepted) {
      delivery.subscribed = true;
    }
    const nextData = {
      delivery,
      canSubscribe:
        !delivery.terminal &&
        delivery.subscribeTemplateIds.length > 0 &&
        typeof wx.requestSubscribeMessage === "function",
      canRate: status === COMPLETED_STATUS && !delivery.rating.rated
    };
    if (!delivery.showMap) {
      nextData.markers = [];
      nextData.polyline = [];
      nextData.mapViewportSafe = false;
      this._riderCommitted = null;
      this.cancelRiderAnimation();
      this._mapFitted = false;
    } else if (!this._riderCommitted) {
      // 首帧：markers 和卡片一起下发，避免地图先渲染一帧空图层
      const point = delivery.rider.point;
      nextData.markers = buildMarkers(delivery, point);
      nextData.polyline = buildPolyline(point, delivery.destination);
      this._riderCommitted = { lat: point.lat, lng: point.lng, fresh: delivery.rider.fresh };
    }
    this.setData(nextData, () => {
      if (delivery.showMap) {
        this.scheduleMapViewportCheck(true);
        this.syncRiderMarker(delivery);
        this.fitMapView();
      }
      this.focusDeliveryCard();
    });
    if (delivery.terminal) {
      this.stopTracking();
      // 送达瞬间同步一次订单，让状态条和评价入口跟着更新
      if (status === DELIVERING_STATUS) {
        this.refreshOrder();
      }
    }
  },

  resetDelivery() {
    this._riderCommitted = null;
    this.cancelRiderAnimation();
    this._mapFitted = false;
    this.setData({
      delivery: { ...EMPTY_DELIVERY },
      markers: [],
      polyline: [],
      mapViewportSafe: false,
      canSubscribe: false,
      canRate: false
    });
  },

  // 平滑移动：只在骑手位置变化时用 translateMarker，图标变化才重建 marker
  syncRiderMarker(delivery) {
    const rider = delivery.rider;
    const point = rider && rider.point;
    if (!point) {
      return;
    }
    if (!this.isMapRenderable()) {
      this.cancelRiderAnimation();
      this.commitRiderPosition(delivery, point);
      return;
    }
    const committed = this._riderCommitted;
    if (!committed || committed.fresh !== rider.fresh) {
      this.cancelRiderAnimation();
      this.commitRiderPosition(delivery, point);
      this.fitMapView();
      return;
    }
    if (committed.lat === point.lat && committed.lng === point.lng) {
      return;
    }
    if (this._riderAnimationActive) {
      this._pendingRiderDelivery = delivery;
      return;
    }
    this.translateRider(delivery, point);
  },

  translateRider(delivery, point) {
    const context = this.getMapContext();
    if (!context || typeof context.translateMarker !== "function") {
      this.commitRiderPosition(delivery, point);
      return;
    }
    const token = this._trackingToken;
    const sequence = this._riderAnimationSequence + 1;
    this._riderAnimationSequence = sequence;
    this._riderAnimationActive = true;
    let settled = false;
    const finish = () => {
      if (settled) {
        return;
      }
      settled = true;
      if (token !== this._trackingToken || sequence !== this._riderAnimationSequence) {
        return;
      }
      this._riderAnimationActive = false;
      // 动画结束后再把数据对齐到终点，marker 已经在该点，不会跳变
      this.commitRiderPosition(delivery, point);
      const pending = this._pendingRiderDelivery;
      this._pendingRiderDelivery = null;
      if (pending) {
        this.syncRiderMarker(pending);
      }
    };
    try {
      context.translateMarker({
        markerId: MARKER_RIDER,
        destination: { latitude: point.lat, longitude: point.lng },
        autoRotate: false,
        rotate: delivery.rider.bearing,
        duration: markerAnimationDuration(this._trackingIntervalMs, RIDER_MOVE_DURATION),
        animationEnd: finish,
        fail: finish
      });
    } catch {
      finish();
    }
  },

  cancelRiderAnimation() {
    this._riderAnimationSequence = (this._riderAnimationSequence || 0) + 1;
    this._riderAnimationActive = false;
    this._pendingRiderDelivery = null;
  },

  commitRiderPosition(delivery, point) {
    this._riderCommitted = { lat: point.lat, lng: point.lng, fresh: delivery.rider.fresh };
    this.setData({
      markers: buildMarkers(delivery, point),
      polyline: buildPolyline(point, delivery.destination)
    });
  },

  getMapContext() {
    if (!this.isMapRenderable()) {
      return null;
    }
    try {
      return wx.createMapContext(MAP_ID, this);
    } catch {
      return null;
    }
  },

  isMapRenderable() {
    return Boolean(
      this.data.delivery
      && this.data.delivery.showMap
      && this.data.mapExpanded
      && this.data.mapViewportSafe
      && !this.data.showPaymentMethodModal
      && !this.data.showCancelModal
      && !this.data.showRatingSheet
      && !this.data.showLotteryChoice
      && !this.data.showLuckyWheel
    );
  },

  // 让骑手和目的地都在视野内；只在首次渲染和用户主动操作时调用，避免和用户拖动打架
  fitMapView(force) {
    if (!this.isMapRenderable()) {
      return;
    }
    if (this._mapFitted && !force) {
      return;
    }
    const points = [];
    if (this._riderCommitted) {
      points.push({ latitude: this._riderCommitted.lat, longitude: this._riderCommitted.lng });
    }
    const destination = this.data.delivery.destination;
    if (destination) {
      points.push({ latitude: destination.lat, longitude: destination.lng });
    }
    if (points.length < 2) {
      return;
    }
    const context = this.getMapContext();
    if (!context || typeof context.includePoints !== "function") {
      return;
    }
    context.includePoints({ points, padding: [60, 60, 60, 60] });
    this._mapFitted = true;
  },

  clearRefitTimer() {
    if (this._refitTimer) {
      clearTimeout(this._refitTimer);
      this._refitTimer = null;
    }
  },

  clearMapViewportTimer() {
    if (this._mapViewportTimer) {
      clearTimeout(this._mapViewportTimer);
      this._mapViewportTimer = null;
    }
  },

  scheduleMapViewportCheck(immediate) {
    if (!this.data.delivery.showMap || !this.data.mapExpanded) {
      return;
    }
    this.clearMapViewportTimer();
    this._mapViewportTimer = setTimeout(() => {
      this._mapViewportTimer = null;
      this.updateMapViewportSafety();
    }, immediate ? 0 : 80);
  },

  updateMapViewportSafety() {
    if (!this.data.delivery.showMap || !this.data.mapExpanded || this._pageVisible === false) {
      return;
    }
    let query;
    try {
      query = typeof this.createSelectorQuery === "function"
        ? this.createSelectorQuery()
        : wx.createSelectorQuery();
      if (!query) {
        return;
      }
      query.select(".delivery-map-wrap").boundingClientRect();
      query.select(".detail-actions").boundingClientRect();
      query.select(".detail-header").boundingClientRect();
    } catch {
      return;
    }
    query.exec((rects) => {
      if (!this.data.delivery.showMap || !this.data.mapExpanded || this._pageVisible === false) {
        return;
      }
      const mapRect = rects && rects[0];
      const actionRect = rects && rects[1];
      const headerRect = rects && rects[2];
      if (!mapRect) {
        return;
      }
      const overlaps = (first, second) => Boolean(
        first
        && second
        && first.top < second.bottom
        && first.bottom > second.top
      );
      const mapViewportSafe = !overlaps(mapRect, actionRect) && !overlaps(mapRect, headerRect);
      if (mapViewportSafe === this.data.mapViewportSafe) {
        return;
      }
      this.setData({ mapViewportSafe }, () => {
        if (mapViewportSafe) {
          this._mapFitted = false;
          this.syncRiderMarker(this.data.delivery);
          this.refitMapSoon();
          return;
        }
        this.cancelRiderAnimation();
        const rider = this.data.delivery.rider;
        if (rider && rider.point) {
          this.commitRiderPosition(this.data.delivery, rider.point);
        }
      });
    });
  },

  // 弹窗关闭或地图展开后，地图组件是重新创建的，需要等一帧再自适应视野
  refitMapSoon() {
    if (!this.data.delivery.showMap) {
      return;
    }
    this.clearRefitTimer();
    this._refitTimer = setTimeout(() => {
      this._refitTimer = null;
      this.fitMapView(true);
    }, 300);
  },

  focusDeliveryCard() {
    if (!this._focusDelivery || !this.data.delivery.available) {
      return;
    }
    this._focusDelivery = false;
    wx.pageScrollTo({
      selector: ".delivery-card",
      offsetTop: -100,
      duration: 300,
      fail() {}
    });
  },

  restoreMapExpanded() {
    let expanded = true;
    try {
      const saved = wx.getStorageSync(MAP_EXPANDED_KEY);
      if (saved === false || saved === "false") {
        expanded = false;
      }
    } catch {}
    this.setData({ mapExpanded: expanded, mapViewportSafe: false });
  },

  toggleDeliveryMap() {
    const mapExpanded = !this.data.mapExpanded;
    this.setData({ mapExpanded, mapViewportSafe: false });
    try {
      wx.setStorageSync(MAP_EXPANDED_KEY, mapExpanded);
    } catch {}
    if (mapExpanded) {
      this._mapFitted = false;
      this.scheduleMapViewportCheck(true);
    } else {
      this.cancelRiderAnimation();
    }
  },

  handleRecenterMap() {
    this.fitMapView(true);
  },

  handleCallRider() {
    const rider = this.data.delivery.rider;
    const phoneNumber = rider && rider.callNumber;
    if (!phoneNumber) {
      wx.showToast({ title: "暂时无法联系骑手", icon: "none" });
      return;
    }
    const dial = () => {
      wx.makePhoneCall({
        phoneNumber,
        fail() {}
      });
    };
    if (rider.phoneDegraded) {
      wx.showModal({
        title: "提示",
        content: "本次为直接拨号，请勿保存骑手号码",
        showCancel: false,
        success: dial
      });
      return;
    }
    dial();
  },

  // 必须由用户点击触发；用户勾选「总是保持以上选择」后不会再弹窗，所以每次进页面都可以再申请
  handleSubscribeDelivery() {
    const templateIds = (this.data.delivery.subscribeTemplateIds || []).slice(0, 3);
    if (!templateIds.length || this.data.subscribing || this.data.delivery.subscribed) {
      return;
    }
    if (typeof wx.requestSubscribeMessage !== "function") {
      wx.showToast({ title: "当前微信版本不支持消息提醒", icon: "none" });
      return;
    }
    this.setData({ subscribing: true });
    wx.requestSubscribeMessage({
      tmplIds: templateIds,
      success: (result) => {
        const accepted = templateIds.filter((id) => result[id] === "accept");
        if (!accepted.length) {
          this.setData({ subscribing: false });
          wx.showToast({ title: "已取消提醒", icon: "none" });
          return;
        }
        subscribeDelivery(this.data.orderId, accepted).then(
          () => {
            this._subscribeAccepted = true;
            this.setData({
              subscribing: false,
              "delivery.subscribed": true
            });
            wx.showToast({ title: "送达时会提醒你", icon: "success" });
          },
          (error) => {
            this._subscribeAccepted = false;
            this.setData({
              subscribing: false,
              "delivery.subscribed": false
            });
            wx.showToast({
              title: (error && error.message) || "提醒登记失败，请稍后重试",
              icon: "none"
            });
          }
        );
      },
      fail: (error) => {
        this.setData({ subscribing: false });
        wx.showToast({ title: subscribeErrorText(error), icon: "none" });
      }
    });
  },

  openRatingSheet() {
    if (!this.data.canRate) {
      return;
    }
    this.setData({
      showRatingSheet: true,
      ratingStar: 5,
      ratingTagList: buildRatingTagList(),
      ratingComment: ""
    });
  },

  stopRatingSheetTap() {},

  closeRatingSheet() {
    if (this.data.ratingSubmitting) {
      return;
    }
    this.setData({ showRatingSheet: false });
    this.refitMapSoon();
  },

  handleRatingStar(event) {
    const star = Number(event.currentTarget.dataset.star || 0);
    if (!star) {
      return;
    }
    this.setData({ ratingStar: Math.max(1, Math.min(5, star)) });
  },

  handleRatingTag(event) {
    const tag = event.currentTarget.dataset.tag;
    if (!tag) {
      return;
    }
    this.setData({
      ratingTagList: this.data.ratingTagList.map((item) =>
        item.tag === tag ? { tag: item.tag, selected: !item.selected } : item
      )
    });
  },

  selectedRatingTags() {
    return this.data.ratingTagList.filter((item) => item.selected).map((item) => item.tag);
  },

  handleRatingComment(event) {
    this.setData({ ratingComment: event.detail.value || "" });
  },

  async confirmRating() {
    if (this.data.ratingSubmitting || !this.data.orderId) {
      return;
    }
    const tags = this.selectedRatingTags();
    const comment = this.data.ratingComment.trim();
    this.setData({ ratingSubmitting: true });
    try {
      await submitRating(this.data.orderId, {
        star: this.data.ratingStar,
        tags,
        comment
      });
      this.setData({
        showRatingSheet: false,
        canRate: false,
        "delivery.rating": {
          rated: true,
          star: this.data.ratingStar,
          tags,
          comment,
          timeText: ""
        }
      });
      wx.showToast({ title: "感谢你的评价", icon: "success" });
    } catch (error) {
      wx.showToast({ title: (error && error.message) || "评价提交失败，请稍后重试", icon: "none" });
    } finally {
      this.setData({ ratingSubmitting: false });
    }
  },

  async handlePay() {
    if (!this.data.orderId || this.data.paying || !this.data.isPendingPayment) {
      return;
    }
    this.setData({
      showPaymentMethodModal: true,
      paymentMethodLoading: true,
      selectedPaymentMethod: this.data.paymentMethod || "WECHAT",
      paymentNotice: ""
    });
    try {
      const method = await getPaymentMethod(this.data.orderId);
      const code = method && method.code === "FRIEND" ? "FRIEND" : "WECHAT";
      this.setData({ paymentMethod: code, selectedPaymentMethod: code });
    } catch (error) {
      this.setData({ showPaymentMethodModal: false });
      wx.showToast({ title: error.message || "支付方式加载失败", icon: "none" });
    } finally {
      this.setData({ paymentMethodLoading: false });
    }
  },

  stopPaymentMethodModalTap() {},

  closePaymentMethodModal() {
    if (!this.data.paymentMethodSubmitting && !this.data.paying) {
      this.setData({ showPaymentMethodModal: false });
      this.refitMapSoon();
    }
  },

  handleSelectPaymentMethod(event) {
    if (this.data.paymentMethodSubmitting || this.data.paymentMethodLoading) return;
    const selectedPaymentMethod = event.currentTarget.dataset.method === "FRIEND" ? "FRIEND" : "WECHAT";
    this.setData({ selectedPaymentMethod });
  },

  async confirmPaymentMethod() {
    if (this.data.paymentMethodSubmitting || this.data.paymentMethodLoading || !this.data.orderId) return;
    const selectedPaymentMethod = this.data.selectedPaymentMethod;
    this.setData({
      paymentMethodSubmitting: true,
      showPaymentMethodModal: false,
      flowPaymentMethod: selectedPaymentMethod,
      paymentNotice: ""
    });
    try {
      await this.continuePendingOrder(selectedPaymentMethod);
    } finally {
      this.setData({ paymentMethodSubmitting: false });
    }
  },

  async continuePendingOrder(paymentMethod) {
    if (!this.data.orderId || this.data.paying || !this.data.isPendingPayment) {
      return;
    }
    this.setData({
      paying: true,
      paymentNotice: "",
      flowPaymentMethod: paymentMethod
    });
    let state = null;
    try {
      state = await getOrderLottery(this.data.orderId);
    } catch (error) {
      this.setData({ paying: false });
      await this.handleLotteryCheckFailure(error, paymentMethod);
      return;
    }
    const intercepted = this.applyLotteryGate(state, paymentMethod);
    this.setData({ paying: false });
    if (intercepted) {
      return;
    }
    await this.runFinalPayment(paymentMethod);
  },

  applyLotteryGate(state, paymentMethod) {
    this.setData({
      lotteryState: state,
      flowPaymentMethod: paymentMethod,
      selectedPaymentMethod: paymentMethod,
      wheelPrizes: (state && state.prizes) || [],
      wheelContinueText: paymentMethod === "FRIEND"
        ? "锁定金额并邀请好友"
        : "按最终金额微信支付"
    });
    if (state && state.drawn) {
      if (!state.result) {
        wx.showModal({
          title: "鲜礼结果同步中",
          content: "本单已经抽取，结果暂未完整返回。请稍后再次点击继续支付恢复结果。",
          showCancel: false
        });
        return true;
      }
      this.openLuckyWheel(state, false);
      return true;
    }
    if (state && lotteryChallengeInvalid(state)) {
      if (state.eligible && lotteryCampaignEnabled(state)) {
        this.setData({
          showPaymentMethodModal: false,
          showLotteryChoice: true,
          showLuckyWheel: false,
          wheelInitialResult: null,
          lotteryResult: null
        });
        return true;
      }
      return false;
    }
    if (state && state.shareTriggered) {
      this.openLuckyWheel(state, false);
      return true;
    }
    if (state && state.eligible && lotteryCampaignEnabled(state)) {
      this.setData({
        showPaymentMethodModal: false,
        showLotteryChoice: true,
        showLuckyWheel: false,
        wheelInitialResult: null,
        lotteryResult: null
      });
      return true;
    }
    clearLotterySession(this.data.orderId, "order-detail");
    return false;
  },

  openLuckyWheel(state, animateResult) {
    const result = state && state.result;
    const nextData = {
      lotteryState: state,
      showPaymentMethodModal: false,
      showLotteryChoice: false,
      showLuckyWheel: true,
      wheelPrizes: (state && state.prizes) || this.data.wheelPrizes || [],
      wheelInitialResult: result && !animateResult ? result : null,
      lotteryResult: result || null,
      drawLoading: false,
      lotteryContinueLoading: false
    };
    if (result) {
      nextData.payableText = yuan(result.payableAmount);
      nextData.lotteryDiscountText = yuan(result.discountAmount);
      nextData.hasLotteryDiscount = Number(result.discountAmount || 0) > 0;
    }
    this.setData(nextData, () => {
      if (!result) {
        return;
      }
      const wheel = this.selectComponent("#detailLuckyWheel");
      if (!wheel) {
        return;
      }
      if (animateResult) {
        wheel.spinTo(result);
      } else {
        wheel.restoreResult(result);
      }
    });
  },

  async handleUnlockLottery() {
    if (this.data.lotteryBusy || !this.data.orderId) {
      return;
    }
    const orderId = Number(this.data.orderId);
    const paymentMethod = this.data.flowPaymentMethod || this.data.paymentMethod || "WECHAT";
    let state = this.data.lotteryState || {};
    this.setData({ lotteryBusy: true });
    try {
      if (!state.challengeToken || lotteryChallengeInvalid(state)) {
        const challenged = await createLotteryChallenge(orderId);
        state = {
          ...state,
          ...challenged,
          campaign: challenged.campaign || state.campaign,
          prizes: challenged.prizes && challenged.prizes.length ? challenged.prizes : state.prizes
        };
      }
      const latest = await getOrderLottery(orderId);
      state = {
        ...state,
        ...latest,
        campaign: latest.campaign || state.campaign,
        prizes: latest.prizes && latest.prizes.length ? latest.prizes : state.prizes
      };
      if (state.drawn || state.shareTriggered) {
        this.applyLotteryGate(state, paymentMethod);
        return;
      }
      if (
        !state.eligible
        || !lotteryCampaignEnabled(state)
        || !state.challengeToken
        || lotteryChallengeInvalid(state)
      ) {
        throw new Error(lotteryReasonText(state));
      }
      writeLotterySession({
        orderId,
        source: "order-detail",
        challengeToken: state.challengeToken,
        paymentMethod,
        action: "pending"
      });
      this.setData({
        lotteryState: state,
        showLotteryChoice: false,
        lotteryBusy: false
      });
      wx.navigateTo({
        url: `/pages/lucky-activity/index?orderId=${orderId}&source=order-detail`,
        fail: () => {
          clearLotterySession(orderId, "order-detail");
          this.setData({ showLotteryChoice: true });
          wx.showToast({ title: "活动页打开失败，请重试", icon: "none" });
        }
      });
    } catch (error) {
      let latest = null;
      try {
        latest = await getOrderLottery(orderId);
      } catch {}
      if (latest && (latest.drawn || latest.shareTriggered)) {
        this.applyLotteryGate(latest, paymentMethod);
        return;
      }
      const originalPay = await modalChoice({
        title: "分享资格暂不可用",
        content: `${(error && error.message) || "分享凭证获取失败"}。可重试，或按原价继续支付。`,
        confirmText: "原价支付",
        cancelText: "留在这里"
      });
      if (originalPay) {
        this.setData({ showLotteryChoice: false });
        await this.runFinalPayment(paymentMethod);
      }
    } finally {
      this.setData({ lotteryBusy: false });
    }
  },

  async handleSkipLottery() {
    if (this.data.lotteryBusy || this.data.paying || !this.data.orderId) {
      return;
    }
    const paymentMethod = this.data.flowPaymentMethod || this.data.paymentMethod || "WECHAT";
    clearLotterySession(this.data.orderId, "order-detail");
    this.setData({ showLotteryChoice: false });
    await this.runFinalPayment(paymentMethod);
  },

  async resumeLotterySession() {
    const session = readLotterySession();
    const orderId = Number(this.data.orderId || 0);
    if (
      this._resumingLottery
      || !session
      || session.source !== "order-detail"
      || Number(session.orderId) !== orderId
      || !orderId
      || !this.data.isPendingPayment
    ) {
      return;
    }
    this._resumingLottery = true;
    const paymentMethod = session.paymentMethod === "FRIEND" ? "FRIEND" : "WECHAT";
    this.setData({ flowPaymentMethod: paymentMethod, selectedPaymentMethod: paymentMethod });
    try {
      if (session.action === "skip") {
        clearLotterySession(orderId, "order-detail");
        this.setData({ showLotteryChoice: false, showLuckyWheel: false });
        await this.runFinalPayment(paymentMethod);
        return;
      }
      if (session.action === "reporting") {
        await new Promise((resolve) => setTimeout(resolve, 350));
      }
      const state = await getOrderLottery(orderId);
      clearLotterySession(orderId, "order-detail");
      if (!this.applyLotteryGate(state, paymentMethod)) {
        await this.runFinalPayment(paymentMethod);
      }
    } catch (error) {
      await this.handleLotteryCheckFailure(error, paymentMethod);
    } finally {
      this._resumingLottery = false;
    }
  },

  async handleLotteryCheckFailure(error, paymentMethod) {
    const originalPay = await modalChoice({
      title: "鲜礼资格暂未确认",
      content: `${(error && error.message) || "网络连接失败"}。可按原价支付，或留在订单详情稍后再试。`,
      confirmText: "原价支付",
      cancelText: "稍后再试"
    });
    if (originalPay) {
      clearLotterySession(this.data.orderId, "order-detail");
      await this.runFinalPayment(paymentMethod);
      return;
    }
    this.refitMapSoon();
  },

  async handleLotteryDraw() {
    if (
      this.data.drawLoading
      || this.data.lotteryContinueLoading
      || !this.data.orderId
    ) {
      return;
    }
    const orderId = Number(this.data.orderId);
    const state = this.data.lotteryState || {};
    const challengeToken = state.challengeToken || "";
    if (!challengeToken) {
      wx.showToast({ title: "分享凭证已失效，请重新进入活动", icon: "none" });
      return;
    }
    this.setData({ drawLoading: true });
    try {
      const beforeDraw = await getOrderLottery(orderId);
      if (beforeDraw.drawn && beforeDraw.result) {
        this.openLuckyWheel(beforeDraw, false);
        return;
      }
      if (lotteryChallengeInvalid(beforeDraw)) {
        this.setData({
          lotteryState: beforeDraw,
          showLuckyWheel: false,
          showLotteryChoice: Boolean(beforeDraw.eligible && lotteryCampaignEnabled(beforeDraw))
        });
        if (beforeDraw.eligible && lotteryCampaignEnabled(beforeDraw)) {
          wx.showToast({ title: "分享凭证已失效，请重新解锁", icon: "none" });
        } else {
          await this.handleLotteryCheckFailure(
            new Error(lotteryReasonText(beforeDraw)),
            this.data.flowPaymentMethod || this.data.paymentMethod
          );
        }
        return;
      }
      if (!beforeDraw.shareTriggered) {
        this.setData({ showLuckyWheel: false });
        this.applyLotteryGate(beforeDraw, this.data.flowPaymentMethod || this.data.paymentMethod);
        wx.showToast({ title: "分享资格尚未生效，请重新确认", icon: "none" });
        return;
      }
      const response = await drawLottery(
        orderId,
        beforeDraw.challengeToken || challengeToken
      );
      const result = response.result;
      if (!result) {
        throw new Error("服务端未返回完整抽奖结果");
      }
      const nextState = {
        ...beforeDraw,
        ...response.state,
        campaign: response.state.campaign || beforeDraw.campaign,
        prizes: response.state.prizes && response.state.prizes.length
          ? response.state.prizes
          : beforeDraw.prizes,
        shareTriggered: true,
        drawn: true,
        result
      };
      this.openLuckyWheel(nextState, true);
    } catch (error) {
      let latest = null;
      try {
        latest = await getOrderLottery(orderId);
      } catch {}
      if (latest && latest.drawn && latest.result) {
        this.openLuckyWheel(latest, true);
        return;
      }
      if (latest && lotteryChallengeInvalid(latest)) {
        this.setData({
          lotteryState: latest,
          showLuckyWheel: false,
          showLotteryChoice: Boolean(latest.eligible && lotteryCampaignEnabled(latest))
        });
        if (latest.eligible && lotteryCampaignEnabled(latest)) {
          wx.showToast({ title: "分享凭证已失效，请重新解锁", icon: "none" });
        } else {
          await this.handleLotteryCheckFailure(
            new Error(lotteryReasonText(latest)),
            this.data.flowPaymentMethod || this.data.paymentMethod
          );
        }
        return;
      }
      if (latest && !latest.shareTriggered) {
        this.setData({ showLuckyWheel: false });
        this.applyLotteryGate(latest, this.data.flowPaymentMethod || this.data.paymentMethod);
        wx.showToast({ title: "分享资格已变化，请重新进入", icon: "none" });
        return;
      }
      wx.showModal({
        title: latest ? "抽奖资格仍在" : "抽奖结果暂未确认",
        content: latest
          ? "本次没有生成中奖结果，资格未消耗，可检查网络后重试。"
          : `${(error && error.message) || "网络连接失败"}。重新进入时会先恢复服务端结果。`,
        showCancel: false
      });
    } finally {
      this.setData({ drawLoading: false });
    }
  },

  handleLotteryFinish() {},

  async handleLuckyWheelContinue() {
    if (this.data.lotteryContinueLoading || this.data.drawLoading) {
      return;
    }
    this.setData({
      showLuckyWheel: false,
      lotteryContinueLoading: true
    });
    await this.runFinalPayment(this.data.flowPaymentMethod || this.data.paymentMethod || "WECHAT");
    this.setData({ lotteryContinueLoading: false });
  },

  async handleLuckyWheelClose(event) {
    if (!this.data.orderId || this.data.drawLoading || this.data.lotteryContinueLoading) {
      return;
    }
    const hasResult = Boolean(
      (event && event.detail && event.detail.hasResult)
      || (this.data.lotteryState && this.data.lotteryState.drawn)
    );
    const continueNow = await modalChoice({
      title: hasResult ? "鲜礼结果已保存" : "暂不抽取鲜礼？",
      content: hasResult
        ? "本单金额已经锁定，可现在继续支付，也可稍后回来恢复。"
        : "按原价支付将不再等待本次分享抽奖；也可以留在订单详情稍后继续。",
      confirmText: hasResult ? "继续支付" : "原价支付",
      cancelText: "稍后再付"
    });
    this.setData({ showLuckyWheel: false });
    this.refitMapSoon();
    if (continueNow) {
      await this.runFinalPayment(this.data.flowPaymentMethod || this.data.paymentMethod || "WECHAT");
    }
  },

  async runFinalPayment(paymentMethod) {
    if (!this.data.orderId || this.data.paying || !this.data.isPendingPayment) {
      return;
    }
    this.setData({
      paying: true,
      lotteryContinueLoading: true,
      paymentNotice: ""
    });
    try {
      if (paymentMethod === "FRIEND") {
        const share = await createPaymentShare(this.data.orderId);
        this.setData({ paymentMethod: "FRIEND" });
        wx.navigateTo({
          url: `/pages/pay-for-other/index?token=${encodeURIComponent(share.token)}&owner=1&orderId=${this.data.orderId}`
        });
        return;
      }

      const payment = await changeToWechatPayment(this.data.orderId);
      this.setData({ paymentMethod: "WECHAT" });
      await requestWechatPayment(payment);
      const order = await waitForPaymentResult(this.data.orderId);
      if (!isPaidOrder(order)) {
        this.setData({ paymentNotice: "支付结果还在确认中，请稍后刷新订单状态。" });
        return;
      }
      this.applyOrder(order);
      wx.showToast({ title: "支付成功", icon: "success" });
    } catch (error) {
      if (paymentMethod === "WECHAT" && this.data.orderId && !isPaymentCancelled(error)) {
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
      this.setData({
        paying: false,
        paymentMethodSubmitting: false,
        lotteryContinueLoading: false
      });
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
    if (this.data.cancelling || this.data.paying) {
      return;
    }
    if (!isCancelableOrder(this.data.order)) {
      wx.showToast({ title: "当前订单不可取消", icon: "none" });
      await this.refreshOrder();
      return;
    }
    this.setData({ showCancelModal: true });
  },

  stopCancelModalTap() {},

  closeCancelModal() {
    if (!this.data.cancelling) {
      this.setData({ showCancelModal: false });
      this.refitMapSoon();
    }
  },

  async handleCancelChoice(event) {
    if (this.data.cancelling) return;
    const value = event.currentTarget.dataset.returnToCart;
    const returnToCart = value === true || value === "true";
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
      this.setData({ cancelling: false, showCancelModal: false });
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
