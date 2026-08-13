const { yuan } = require("../../utils/format");
const { getAddresses } = require("../../api/addresses");
const { getCart } = require("../../api/cart");
const { getHome } = require("../../api/catalog");
const { getDeliverySlots } = require("../../api/delivery");
const {
  createOrder,
  createPaymentShare,
  getPaymentShare,
  payOrder,
  previewOrder
} = require("../../api/orders");
const {
  createLotteryChallenge,
  drawLottery,
  getOrderLottery
} = require("../../api/marketing");
const { requireCompleteProfile } = require("../../utils/auth-guard");
const { syncTheme } = require("../../utils/theme");
const { buildMinOrderState, normalizeMinOrderAmount } = require("../../utils/min-order");
const {
  clearLotterySession,
  readLotterySession,
  writeLotterySession
} = require("../../utils/lottery-session");
const {
  hasActivePaymentShare,
  isChallengeInvalid: lotteryChallengeInvalid,
  lotteryCampaignEnabled,
  lotteryReasonText
} = require("../../utils/lottery-status");
const {
  inspectPaymentShare,
  rememberPaymentShareToken
} = require("../../utils/payment-share-store");
const {
  pendingOrderDecision,
  pendingOrderLockKind,
  pendingOrderSignature
} = require("./pending-order");
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
  lotteryDiscountText: "0.00",
  hasLotteryDiscount: false,
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
    lotteryDiscountText: "0.00",
    hasLotteryDiscount: false,
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

function modalChoice(options) {
  return new Promise((resolve) => {
    wx.showModal({
      ...options,
      success: (result) => resolve(Boolean(result.confirm)),
      fail: () => resolve(false)
    });
  });
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
    lotteryDiscountText: "0.00",
    hasLotteryDiscount: false,
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
    paymentMethod: "WECHAT",
    pendingOrderId: 0,
    pendingOrderSignature: "",
    flowPaymentMethod: "WECHAT",
    lotteryState: null,
    lotteryBusy: false,
    showLotteryChoice: false,
    showLuckyWheel: false,
    wheelPrizes: [],
    wheelInitialResult: null,
    lotteryResult: null,
    drawLoading: false,
    lotteryContinueLoading: false,
    wheelContinueText: "按最终金额微信支付"
  },

  onLoad(options = {}) {
    this._resumingLottery = false;
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
    this.resumeLotterySession();
    const selectedAddress = wx.getStorageSync("checkoutSelectedAddress");
    if (!selectedAddress || !selectedAddress.id) {
      return;
    }
    wx.removeStorageSync("checkoutSelectedAddress");
    this.applySelectedAddress(selectedAddress);
  },

  async applySelectedAddress(selectedAddress) {
    const changed = Number(selectedAddress.id) !== Number((this.data.address && this.data.address.id) || 0);
    if (changed && Number(this.data.pendingOrderId)) {
      if (!(await this.confirmPendingOrderChange())) {
        return;
      }
      this.clearPendingOrder();
    }
    if (!hasOrderSource(this) || !this.data.activeSlotId) {
      this.setData({ address: selectedAddress });
      this.loadCheckout();
      return;
    }
    await this.refreshPreview(selectedAddress, this.data.activeSlotId);
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
      // 重新加载相当于换了一单，旧的待支付订单与鲜礼状态都不能再复用
      pendingOrderId: 0,
      pendingOrderSignature: "",
      lotteryState: null,
      showLotteryChoice: false,
      showLuckyWheel: false,
      wheelPrizes: [],
      wheelInitialResult: null,
      lotteryResult: null,
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

  currentOrderSignature() {
    return pendingOrderSignature({
      addressId: this.data.address && this.data.address.id,
      deliverySlotId: this.data.activeSlotId,
      remark: this.data.remark,
      buyNowRequest: this.data.buyNowRequest,
      cartItemIds: this.data.cartItemIds
    });
  },

  // 作废本页缓存的待支付订单，下次付款会按当前地址/时段/备注重新建单
  clearPendingOrder() {
    const orderId = Number(this.data.pendingOrderId || 0);
    if (orderId) {
      clearLotterySession(orderId, "checkout");
    }
    this.setData({
      pendingOrderId: 0,
      pendingOrderSignature: "",
      lotteryState: null,
      showLotteryChoice: false,
      showLuckyWheel: false,
      wheelPrizes: [],
      wheelInitialResult: null,
      lotteryResult: null,
      lotteryDiscountText: "0.00",
      hasLotteryDiscount: false
    });
  },

  // 已经抽到鲜礼或已用分享换到资格的订单，作废前必须让用户知道代价
  async confirmPendingOrderChange() {
    const lockKind = pendingOrderLockKind(this.data.lotteryState);
    if (!Number(this.data.pendingOrderId) || !lockKind) {
      return true;
    }
    return modalChoice({
      title: "本单已锁定鲜礼",
      content: lockKind === "DRAWN"
        ? "本单已经抽到鲜礼并锁定金额。修改后需要重新下单并重新抽取，本次抽到的减免不会保留。"
        : "本单已经用一次朋友圈分享解锁了抽奖资格。修改后需要重新下单并重新分享解锁。",
      confirmText: "仍要修改",
      cancelText: "保持原单"
    });
  },

  async chooseSlot(event) {
    const activeSlotId = Number(event.currentTarget.dataset.id);
    if (!activeSlotId || activeSlotId === Number(this.data.activeSlotId)) {
      return;
    }
    if (Number(this.data.pendingOrderId)) {
      if (!(await this.confirmPendingOrderChange())) {
        return;
      }
      this.clearPendingOrder();
    }
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
    if (!canPay) {
      this.setData({ payDisabled: true });
      wx.showToast({ title: "订单信息不完整", icon: "none" });
      return;
    }

    // 地址、时段、备注、商品来源任何一项变化，旧的待支付订单都不能再复用
    const signature = this.currentOrderSignature();
    const decision = pendingOrderDecision({
      pendingOrderId: this.data.pendingOrderId,
      pendingSignature: this.data.pendingOrderSignature,
      signature,
      lotteryState: this.data.lotteryState
    });
    if (decision.action === "confirm" && !(await this.confirmPendingOrderChange())) {
      return;
    }
    if (decision.action === "confirm" || decision.action === "recreate") {
      const hadLotteryDiscount = this.data.hasLotteryDiscount;
      this.clearPendingOrder();
      if (hadLotteryDiscount) {
        await this.refreshPreview(this.data.address, this.data.activeSlotId);
      }
    }

    let orderId = decision.action === "reuse" ? Number(this.data.pendingOrderId || 0) : 0;
    this.setData({
      paying: true,
      payDisabled: true,
      flowPaymentMethod: paymentMethod
    });
    try {
      if (!orderId) {
        const order = await createOrder({
          addressId: this.data.address.id,
          deliverySlotId: this.data.activeSlotId,
          ...orderSourcePayload(this),
          remark: (this.data.remark || "").trim()
        });
        orderId = Number(order.id || 0);
        if (!orderId) {
          throw new Error("订单创建成功但未返回订单编号");
        }
        this.setData({ pendingOrderId: orderId, pendingOrderSignature: signature });
      }
    } catch (error) {
      const message = paymentErrorMessage(error, "订单提交失败，请重试");
      if (String(message || "").indexOf("起送") >= 0) {
        wx.showToast({ title: message, icon: "none" });
      } else {
        wx.showModal({
          title: "订单提交失败",
          content: message,
          showCancel: false
        });
      }
      this.setData({
        paying: false,
        payDisabled: !this.data.minOrderMet
      });
      return;
    } finally {
      this.setData({
        paying: false,
        payDisabled: !this.data.minOrderMet
      });
    }

    await this.continueCreatedOrder(orderId, paymentMethod);
  },

  async continueCreatedOrder(orderId, paymentMethod) {
    if (!orderId || this.data.paying) {
      return;
    }
    this.setData({
      paying: true,
      payDisabled: true,
      flowPaymentMethod: paymentMethod
    });
    let state = null;
    try {
      state = await getOrderLottery(orderId);
    } catch (error) {
      this.setData({
        paying: false,
        payDisabled: !this.data.minOrderMet
      });
      await this.handleLotteryCheckFailure(error, orderId, paymentMethod);
      return;
    }

    const intercepted = this.applyLotteryGate(state, paymentMethod);
    this.setData({
      paying: false,
      payDisabled: !this.data.minOrderMet
    });
    if (intercepted) {
      return;
    }
    await this.runFinalPayment(orderId, paymentMethod);
  },

  applyLotteryGate(state, paymentMethod) {
    const orderId = Number(this.data.pendingOrderId || 0);
    this.setData({
      lotteryState: state,
      flowPaymentMethod: paymentMethod,
      wheelPrizes: (state && state.prizes) || [],
      wheelContinueText: paymentMethod === "FRIEND"
        ? "锁定金额并邀请好友"
        : "按最终金额微信支付"
    });

    if (state && state.drawn) {
      if (!state.result) {
        wx.showModal({
          title: "鲜礼结果同步中",
          content: "本单已经抽取，结果暂未完整返回。请稍后再次点击付款恢复结果。",
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
        showLotteryChoice: true,
        showLuckyWheel: false,
        wheelInitialResult: null,
        lotteryResult: null
      });
      return true;
    }
    if (orderId) {
      clearLotterySession(orderId, "checkout");
    }
    return false;
  },

  openLuckyWheel(state, animateResult) {
    const result = state && state.result;
    const nextData = {
      lotteryState: state,
      showLotteryChoice: false,
      showLuckyWheel: true,
      wheelPrizes: (state && state.prizes) || this.data.wheelPrizes || [],
      wheelInitialResult: result && !animateResult ? result : null,
      lotteryResult: result || null,
      drawLoading: false,
      lotteryContinueLoading: false
    };
    if (result) {
      nextData.totalText = yuan(result.payableAmount);
      nextData.lotteryDiscountText = yuan(result.discountAmount);
      nextData.hasLotteryDiscount = Number(result.discountAmount || 0) > 0;
    }
    this.setData(nextData, () => {
      if (!result) {
        return;
      }
      const wheel = this.selectComponent("#checkoutLuckyWheel");
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
    if (this.data.lotteryBusy || !this.data.pendingOrderId) {
      return;
    }
    const orderId = Number(this.data.pendingOrderId);
    const paymentMethod = this.data.flowPaymentMethod || this.data.paymentMethod;
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
        source: "checkout",
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
        url: `/pages/lucky-activity/index?orderId=${orderId}&source=checkout`,
        fail: () => {
          clearLotterySession(orderId, "checkout");
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
        await this.runFinalPayment(orderId, paymentMethod);
      }
    } finally {
      this.setData({ lotteryBusy: false });
    }
  },

  async handleSkipLottery() {
    if (this.data.lotteryBusy || this.data.paying || !this.data.pendingOrderId) {
      return;
    }
    const orderId = Number(this.data.pendingOrderId);
    const paymentMethod = this.data.flowPaymentMethod || this.data.paymentMethod;
    clearLotterySession(orderId, "checkout");
    this.setData({ showLotteryChoice: false });
    await this.runFinalPayment(orderId, paymentMethod);
  },

  async resumeLotterySession() {
    const session = readLotterySession();
    const orderId = Number(this.data.pendingOrderId || 0);
    if (
      this._resumingLottery
      || !session
      || session.source !== "checkout"
      || Number(session.orderId) !== orderId
      || !orderId
    ) {
      return;
    }
    this._resumingLottery = true;
    const paymentMethod = session.paymentMethod === "FRIEND" ? "FRIEND" : "WECHAT";
    this.setData({ flowPaymentMethod: paymentMethod });
    try {
      if (session.action === "skip") {
        clearLotterySession(orderId, "checkout");
        this.setData({ showLotteryChoice: false, showLuckyWheel: false });
        await this.runFinalPayment(orderId, paymentMethod);
        return;
      }
      if (session.action === "reporting") {
        await new Promise((resolve) => setTimeout(resolve, 350));
      }
      const state = await getOrderLottery(orderId);
      clearLotterySession(orderId, "checkout");
      // 等待期间订单可能已被作废（例如用户顺手改了地址），此时不能再恢复它的鲜礼状态
      if (Number(this.data.pendingOrderId) !== orderId) {
        return;
      }
      // 用户只是从活动页返回，没有点过付款；这里只更新状态，付款交还给用户点击
      if (!this.applyLotteryGate(state, paymentMethod)) {
        wx.showToast({
          title: lotteryReasonText(state, "本单暂不能参与鲜礼，可直接点击付款"),
          icon: "none"
        });
      }
    } catch (error) {
      await this.handleLotteryCheckFailure(error, orderId, paymentMethod, { allowPay: false });
    } finally {
      this._resumingLottery = false;
    }
  },

  async handleLotteryCheckFailure(error, orderId, paymentMethod, options = {}) {
    if (options.allowPay === false) {
      wx.showModal({
        title: "鲜礼资格暂未确认",
        content: `${(error && error.message) || "网络连接失败"}。订单已保留，可稍后再点击付款，或在订单详情继续处理。`,
        showCancel: false
      });
      return;
    }
    const originalPay = await modalChoice({
      title: "鲜礼资格暂未确认",
      content: `${(error && error.message) || "网络连接失败"}。可按原价支付，或稍后在订单详情继续确认。`,
      confirmText: "原价支付",
      cancelText: "稍后再付"
    });
    if (originalPay) {
      clearLotterySession(orderId, "checkout");
      await this.runFinalPayment(orderId, paymentMethod);
      return;
    }
    wx.redirectTo({ url: `/pages/order-detail/index?id=${orderId}` });
  },

  async handleLotteryDraw() {
    if (
      this.data.drawLoading
      || this.data.lotteryContinueLoading
      || !this.data.pendingOrderId
    ) {
      return;
    }
    const orderId = Number(this.data.pendingOrderId);
    let state = this.data.lotteryState || {};
    const challengeToken = state.challengeToken || "";
    if (!challengeToken) {
      wx.showToast({ title: "分享凭证已失效，请重新进入活动", icon: "none" });
      return;
    }
    this.setData({ drawLoading: true });
    try {
      const beforeDraw = await getOrderLottery(orderId);
      state = beforeDraw;
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
            orderId,
            this.data.flowPaymentMethod
          );
        }
        return;
      }
      if (!beforeDraw.shareTriggered) {
        this.setData({ showLuckyWheel: false });
        this.applyLotteryGate(beforeDraw, this.data.flowPaymentMethod);
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
            orderId,
            this.data.flowPaymentMethod
          );
        }
        return;
      }
      if (latest && !latest.shareTriggered) {
        this.setData({ showLuckyWheel: false });
        this.applyLotteryGate(latest, this.data.flowPaymentMethod);
        wx.showToast({ title: "分享资格已变化，请重新进入", icon: "none" });
        return;
      }
      wx.showModal({
        title: latest ? "抽奖资格仍在" : "抽奖结果暂未确认",
        content: latest
          ? "本次没有生成中奖结果，资格未消耗，可检查网络后重试。"
          : `${(error && error.message) || "网络连接失败"}。请保留订单，重新进入后会先恢复服务端结果。`,
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
    const orderId = Number(this.data.pendingOrderId || 0);
    const paymentMethod = this.data.flowPaymentMethod || this.data.paymentMethod;
    if (!orderId) {
      return;
    }
    this.setData({
      showLuckyWheel: false,
      lotteryContinueLoading: true
    });
    await this.runFinalPayment(orderId, paymentMethod);
    this.setData({ lotteryContinueLoading: false });
  },

  async handleLuckyWheelClose(event) {
    const orderId = Number(this.data.pendingOrderId || 0);
    if (!orderId || this.data.drawLoading || this.data.lotteryContinueLoading) {
      return;
    }
    const detail = (event && event.detail) || {};
    const paymentMethod = this.data.flowPaymentMethod || this.data.paymentMethod;
    const hasResult = Boolean(
      detail.hasResult
      || (this.data.lotteryState && this.data.lotteryState.drawn)
    );
    this.setData({ showLuckyWheel: false });
    // 点遮罩只是收起弹层，绝不触发任何支付动作
    if (detail.source === "mask") {
      wx.showToast({
        title: hasResult ? "鲜礼已保存，可再次点击付款" : "已收起，可再次点击付款继续抽取",
        icon: "none"
      });
      return;
    }
    if (!hasResult) {
      const keepDrawing = await modalChoice({
        title: "先不抽鲜礼？",
        content: paymentMethod === "FRIEND"
          ? "生成代付链接后本单会按原价锁定，不再参与本次鲜礼抽奖。"
          : "按原价支付后本单不再参与本次鲜礼抽奖；也可以先抽完再付款。",
        confirmText: "继续抽奖",
        cancelText: "原价支付"
      });
      if (keepDrawing) {
        this.setData({ showLuckyWheel: true });
        return;
      }
      await this.runFinalPayment(orderId, paymentMethod);
      return;
    }
    const continueNow = await modalChoice({
      title: "鲜礼结果已保存",
      content: "本单金额已经锁定，可现在继续支付，也可稍后在订单详情恢复。",
      confirmText: "继续支付",
      cancelText: "稍后再付"
    });
    if (continueNow) {
      await this.runFinalPayment(orderId, paymentMethod);
      return;
    }
    wx.redirectTo({ url: `/pages/order-detail/index?id=${orderId}` });
  },

  // 重新调用 createPaymentShare 会作废好友手上的旧链接并轮换支付单号，先确认有没有可复用的链接
  async resolveFriendPaymentShare(orderId) {
    const existing = await inspectPaymentShare(orderId, getPaymentShare);
    if (existing.state === "REUSABLE") {
      wx.showToast({ title: "本单已有代付链接，直接打开", icon: "none" });
      return { token: existing.token };
    }
    if (existing.state === "PAID") {
      wx.showToast({ title: "好友已完成付款", icon: "none" });
      wx.redirectTo({ url: `/pages/order-detail/index?id=${orderId}` });
      return null;
    }
    const needsConfirm = existing.state === "UNKNOWN" || hasActivePaymentShare(this.data.lotteryState);
    if (needsConfirm) {
      const regenerate = await modalChoice({
        title: "本单已有代付链接",
        content: existing.state === "UNKNOWN"
          ? "暂时无法确认已生成的代付链接是否仍然有效。重新生成会让好友手上的旧链接立刻失效。"
          : "本单已经生成过好友代付链接。重新生成会让好友手上的旧链接立刻失效。",
        confirmText: "重新生成",
        cancelText: "先不生成"
      });
      if (!regenerate) {
        return null;
      }
    }
    const share = await createPaymentShare(orderId);
    rememberPaymentShareToken(orderId, share.token);
    return share;
  },

  async runFinalPayment(orderId, paymentMethod) {
    if (!orderId || this.data.paying) {
      return;
    }
    this.setData({
      paying: true,
      payDisabled: true,
      lotteryContinueLoading: true
    });
    try {
      if (paymentMethod === "FRIEND") {
        const share = await this.resolveFriendPaymentShare(orderId);
        if (!share) {
          return;
        }
        wx.redirectTo({
          url: `/pages/pay-for-other/index?token=${encodeURIComponent(share.token)}&owner=1&orderId=${orderId}`
        });
        return;
      }
      const payment = await payOrder(orderId);
      await requestWechatPayment(payment);
      const latestOrder = await waitForPaymentResult(orderId);
      if (!isPaidOrder(latestOrder)) {
        showPaymentPending(this, orderId);
        return;
      }
      wx.showToast({ title: "支付成功", icon: "success" });
      wx.redirectTo({ url: `/pages/order-detail/index?id=${orderId}` });
    } catch (error) {
      await this.handleFinalPaymentError(error, orderId, paymentMethod);
    } finally {
      this.setData({
        paying: false,
        payDisabled: !this.data.minOrderMet,
        lotteryContinueLoading: false
      });
    }
  },

  async handleFinalPaymentError(error, orderId, paymentMethod) {
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
      this.setData({ payDisabled: true });
      return;
    }
    wx.showModal({
      title: "支付失败",
      content: message,
      showCancel: false
    });
  }
});
