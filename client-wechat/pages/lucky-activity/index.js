const {
  createLotteryChallenge,
  drawLottery,
  getOrderLottery,
  getPublicLottery,
  recoverLotteryDrawAfterFailure,
  reportLotteryShareTrigger
} = require("../../api/marketing");
const { yuan } = require("../../utils/format");
const { mergeLotteryState } = require("../../utils/lottery-flow");
const {
  readLotterySession,
  updateLotterySession
} = require("../../utils/lottery-session");
const {
  campaignNotice,
  isChallengeInvalid: challengeInvalid,
  lotteryCampaignEnabled: campaignEnabled,
  lotteryReasonText: stateMessage
} = require("../../utils/lottery-status");

const LANDING_SCENE = 1154;

function getCurrentScene(options = {}) {
  let enterOptions = {};
  try {
    enterOptions = typeof wx.getEnterOptionsSync === "function" ? wx.getEnterOptionsSync() : {};
  } catch {}
  return Number(options.scene || enterOptions.scene || 0);
}

function publicShareData(campaign) {
  const current = campaign || {};
  return {
    title: current.shareTitle || current.name || "禹邻优鲜｜菜篮鲜礼签",
    query: "landing=1",
    imageUrl: current.shareImageUrl || ""
  };
}

function publicFriendShareData(campaign) {
  const timeline = publicShareData(campaign);
  return {
    title: timeline.title,
    path: "/pages/lucky-activity/index?landing=1",
    imageUrl: timeline.imageUrl
  };
}

function delay(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

function isGiftResult(result) {
  const type = String((result && result.prizeType) || "").toUpperCase();
  return Boolean(
    result
    && (
      result.productId
      || result.skuId
      || (result.gifts || []).length
      || ["PRODUCT", "GIFT", "PHYSICAL", "SKU", "GOODS"].includes(type)
      || type.includes("GIFT")
      || type.includes("PRODUCT")
    )
  );
}

function resultCopy(result) {
  const discountAmount = Number((result && result.discountAmount) || 0);
  return {
    resultTitle: (result && result.prizeName) || (discountAmount > 0 ? "本单减免" : "菜篮鲜礼"),
    resultDiscountText: discountAmount > 0 ? `本单立减 ¥${yuan(discountAmount)}` : "",
    resultPayableText: yuan(result && result.payableAmount),
    resultHasGift: isGiftResult(result)
  };
}

function pageCopy(phase, campaign) {
  if (phase === "result") {
    return {
      pageTitle: "开奖结果",
      pageDesc: "结果已由服务端锁定，可按最终金额继续支付"
    };
  }
  if (phase === "draw") {
    return {
      pageTitle: "分享资格已点亮",
      pageDesc: "资格已确认，转动这只转盘抽取本单鲜礼"
    };
  }
  if (phase === "share") {
    return {
      pageTitle: campaign.name || "分享后抽取本单鲜礼",
      pageDesc: campaign.shareDescription || "点右上角分享到朋友圈后，同一只转盘即可开奖"
    };
  }
  return {
    pageTitle: campaign.name || "把一篮新鲜，也分享给你",
    pageDesc: campaign.shareDescription || "符合条件并分享活动页的订单，可抽取一次随机减免。"
  };
}

function resolvePhase({ landingMode, drawn, shareTriggered }) {
  if (landingMode) {
    return "landing";
  }
  if (drawn) {
    return "result";
  }
  if (shareTriggered) {
    return "draw";
  }
  return "share";
}

Page({
  data: {
    landingMode: false,
    loading: true,
    loadError: "",
    campaign: {},
    prizes: [],
    campaignNotice: null,
    orderId: 0,
    source: "",
    challengeToken: "",
    shareReady: false,
    shareMenuAvailable: true,
    reporting: false,
    shareReported: false,
    statusText: "",
    phase: "share",
    pageTitle: "菜篮鲜礼签",
    pageDesc: "",
    spinning: false,
    drawLoading: false,
    continueLoading: false,
    continueText: "按最终金额继续支付",
    wheelInitialResult: null,
    resultTitle: "",
    resultDiscountText: "",
    resultPayableText: "0.00",
    resultHasGift: false
  },

  onLoad(options = {}) {
    this._shareCallbackObserved = false;
    this._finishingShare = false;
    this._returned = false;
    this._showCount = 0;
    const session = readLotterySession();
    const hasOrderContext = Boolean(
      Number(options.orderId)
      && options.source
      && session
      && Number(session.orderId) === Number(options.orderId)
      && session.source === options.source
    );
    const landingMode = !hasOrderContext
      && (getCurrentScene(options) === LANDING_SCENE || options.landing === "1");
    const paymentMethod = session && session.paymentMethod === "FRIEND" ? "FRIEND" : "WECHAT";
    this.setData({
      landingMode,
      continueText: paymentMethod === "FRIEND" ? "锁定金额并邀请好友" : "按最终金额继续支付"
    });
    this.enableTimelineMenu();
    if (landingMode) {
      this.loadLandingContent();
      return;
    }
    this.loadOrderWheel(options);
  },

  onShow() {
    this._showCount += 1;
    this.enableTimelineMenu();
    if (
      this.data.landingMode
      || this._showCount === 1
      || !this._shareCallbackObserved
      || this._returned
    ) {
      return;
    }
    this.finishShareRound();
  },

  onShareTimeline() {
    const shareData = publicShareData(this.data.campaign);
    if (this.data.landingMode) {
      return shareData;
    }
    if (
      this.data.phase !== "share"
      || !this.data.shareReady
      || !this.data.orderId
      || !this.data.challengeToken
      || this.data.reporting
    ) {
      const hint = this.data.phase !== "share"
        ? "本单已记录资格，无需再次分享"
        : this.data.reporting
          ? "上一次分享正在记录，请稍候"
          : "活动还没准备好，请稍候再分享";
      wx.showToast({ title: hint, icon: "none" });
      this.setData({ statusText: hint });
      return shareData;
    }

    this._shareCallbackObserved = true;
    this.setData({
      reporting: true,
      shareReported: false,
      statusText: "正在记录分享入口，关闭面板后会核对资格"
    });
    updateLotterySession({ action: "reporting" });
    this._shareReportPromise = reportLotteryShareTrigger(
      this.data.orderId,
      this.data.challengeToken
    )
      .then(() => getOrderLottery(this.data.orderId))
      .then((state) => ({ ok: true, state }))
      .catch((error) => ({ ok: false, error }));
    this._shareReportPromise.then((report) => {
      if (this._returned || !report.ok || !report.state) {
        return;
      }
      if (!report.state.shareTriggered && !report.state.drawn) {
        return;
      }
      this.applyOrderState(report.state, {
        reporting: false,
        shareReported: true,
        statusText: "分享入口已记录，可以抽取本单鲜礼"
      });
    });
    return shareData;
  },

  onShareAppMessage() {
    return publicFriendShareData(this.data.campaign);
  },

  enableTimelineMenu() {
    if (typeof wx.showShareMenu !== "function") {
      this.setData({ shareMenuAvailable: false });
      return;
    }
    wx.showShareMenu({
      withShareTicket: false,
      menus: ["shareAppMessage", "shareTimeline"],
      success: () => this.setData({ shareMenuAvailable: true }),
      fail: () => this.setData({ shareMenuAvailable: false })
    });
  },

  applyOrderState(state, extra = {}) {
    const campaign = (state && state.campaign) || this.data.campaign || {};
    const drawn = Boolean(state && state.drawn && state.result);
    const shareTriggered = Boolean(state && (state.shareTriggered || drawn));
    const phase = extra.phase || resolvePhase({
      landingMode: this.data.landingMode,
      drawn,
      shareTriggered
    });
    const copy = pageCopy(phase, campaign);
    const result = drawn ? resultCopy(state.result) : {};
    const challengeToken = (state && state.challengeToken) || this.data.challengeToken || "";
    this.setData({
      campaign,
      prizes: (state && state.prizes) || this.data.prizes || [],
      challengeToken,
      shareReady: Boolean(challengeToken && phase === "share"),
      shareReported: shareTriggered || this.data.shareReported,
      phase,
      pageTitle: copy.pageTitle,
      pageDesc: copy.pageDesc,
      wheelInitialResult: drawn ? state.result : null,
      ...result,
      ...extra
    });
    if (challengeToken) {
      updateLotterySession({
        challengeToken,
        action: drawn ? "drawn" : shareTriggered ? "shared" : "pending"
      });
    }
    return phase;
  },

  async loadLandingContent() {
    this.setData({ loading: true, loadError: "" });
    try {
      const state = await getPublicLottery();
      const notice = campaignNotice(state.campaign);
      const campaign = state.campaign || {};
      const copy = pageCopy("landing", campaign);
      this.setData({
        campaign,
        prizes: state.prizes || [],
        campaignNotice: notice,
        phase: "landing",
        pageTitle: copy.pageTitle,
        pageDesc: copy.pageDesc,
        loadError: ""
      });
    } catch (error) {
      this.setData({
        campaignNotice: null,
        loadError: (error && error.message) || "活动信息暂时没有加载出来"
      });
    } finally {
      this.setData({ loading: false });
    }
  },

  async loadOrderWheel(options = {}) {
    const session = readLotterySession();
    const orderId = Number(options.orderId || (session && session.orderId) || 0);
    const source = options.source || (session && session.source) || "";
    if (!session || Number(session.orderId) !== orderId || session.source !== source) {
      this.setData({
        loading: false,
        loadError: "分享入口已失效，请返回付款页重新进入"
      });
      return;
    }
    this.setData({
      loading: true,
      loadError: "",
      orderId,
      source,
      challengeToken: session.challengeToken || ""
    });
    try {
      let state = await getOrderLottery(orderId);
      if (
        !state.drawn
        && !state.shareTriggered
        && (!state.challengeToken || challengeInvalid(state))
      ) {
        const challenged = await createLotteryChallenge(orderId);
        state = mergeLotteryState(state, challenged);
        state = mergeLotteryState(state, await getOrderLottery(orderId));
      }
      if (state.drawn && !state.result) {
        this.setData({
          campaign: state.campaign || {},
          prizes: state.prizes || [],
          loadError: "本单已经抽取，结果暂未完整返回。请稍后重新进入。"
        });
        return;
      }
      if (!state.drawn && !state.shareTriggered && (!state.eligible || !campaignEnabled(state))) {
        this.setData({
          campaign: state.campaign || {},
          prizes: state.prizes || [],
          loadError: stateMessage(state, "本单暂不符合活动条件")
        });
        return;
      }
      if (!state.drawn && !state.shareTriggered && (!state.challengeToken || challengeInvalid(state))) {
        this.setData({
          campaign: state.campaign || {},
          prizes: state.prizes || [],
          loadError: "分享凭证已失效，请返回付款页重新进入"
        });
        return;
      }
      this.applyOrderState(state, { statusText: "" });
    } catch (error) {
      this.setData({
        loadError: (error && error.message) || "活动状态加载失败，请检查网络后重试"
      });
    } finally {
      this.setData({ loading: false });
    }
  },

  async finishShareRound() {
    if (this._finishingShare || this._returned) {
      return;
    }
    this._finishingShare = true;
    try {
      const report = this._shareReportPromise
        ? await this._shareReportPromise
        : { ok: false, error: new Error("本次分享入口未成功记录") };
      let state = report.state || null;
      if (!state || (!state.shareTriggered && !state.drawn)) {
        await delay(350);
        try {
          state = await getOrderLottery(this.data.orderId);
        } catch (error) {
          if (!report.ok) {
            throw report.error || error;
          }
          throw error;
        }
      }
      if (state && (state.shareTriggered || state.drawn)) {
        this.applyOrderState(state, {
          reporting: false,
          shareReported: true,
          statusText: state.drawn ? "本单已经开过签" : "分享入口已记录，可以抽取本单鲜礼"
        });
        return;
      }
      if (state && state.eligible && campaignEnabled(state) && !challengeInvalid(state)) {
        this.applyOrderState(state, {
          reporting: false,
          statusText: "资格尚未记录，请重新从右上角进入朋友圈分享"
        });
        this._shareCallbackObserved = false;
        return;
      }
      if (state && challengeInvalid(state)) {
        updateLotterySession({ action: "pending" });
        this._shareCallbackObserved = false;
        this.setData({
          reporting: false,
          loadError: stateMessage(state, "分享凭证已失效，请返回付款页重新进入")
        });
        return;
      }
      this.setData({
        reporting: false,
        loadError: stateMessage(state, "活动状态已变化，请返回付款页继续")
      });
    } catch (error) {
      updateLotterySession({ action: "pending" });
      this._shareCallbackObserved = false;
      this.setData({
        reporting: false,
        shareReported: false,
        statusText: "",
        loadError: `${(error && error.message) || "网络连接失败"}。本次未确认资格，请重试或按原价支付`
      });
    } finally {
      this._finishingShare = false;
    }
  },

  wheel() {
    return this.selectComponent("#activityLuckyWheel");
  },

  async handleDraw() {
    if (
      this.data.landingMode
      || this.data.phase !== "draw"
      || this.data.drawLoading
      || this.data.spinning
      || !this.data.orderId
    ) {
      return;
    }
    const orderId = Number(this.data.orderId);
    const challengeToken = this.data.challengeToken || "";
    if (!challengeToken) {
      wx.showToast({ title: "分享凭证已失效，请重新进入活动", icon: "none" });
      return;
    }
    this.setData({ drawLoading: true, statusText: "" });
    try {
      const beforeDraw = await getOrderLottery(orderId);
      if (beforeDraw.drawn && beforeDraw.result) {
        this.applyOrderState(beforeDraw);
        const wheel = this.wheel();
        if (wheel) {
          wheel.restoreResult(beforeDraw.result);
        }
        return;
      }
      if (challengeInvalid(beforeDraw)) {
        this.applyOrderState(beforeDraw, {
          loadError: beforeDraw.eligible && campaignEnabled(beforeDraw)
            ? "分享凭证已失效，请重新从右上角分享"
            : stateMessage(beforeDraw)
        });
        return;
      }
      if (!beforeDraw.shareTriggered) {
        this.applyOrderState(beforeDraw, {
          statusText: "分享资格尚未生效，请重新从右上角进入朋友圈分享"
        });
        this._shareCallbackObserved = false;
        return;
      }
      const response = await drawLottery(orderId, beforeDraw.challengeToken || challengeToken);
      const result = response.result;
      if (!result) {
        throw new Error("服务端未返回完整抽奖结果");
      }
      const nextState = mergeLotteryState(beforeDraw, {
        ...response.state,
        shareTriggered: true,
        drawn: true,
        result
      });
      this.applyOrderState(nextState, {
        phase: "draw",
        spinning: true,
        wheelInitialResult: null
      });
      const wheel = this.wheel();
      if (wheel) {
        wheel.spinTo(result);
      } else {
        this.handleWheelFinish({ detail: { result } });
      }
    } catch (error) {
      let latest = null;
      try {
        latest = await getOrderLottery(orderId);
      } catch {}
      const recovery = recoverLotteryDrawAfterFailure({
        getState: latest,
        getFailed: !latest
      });
      if (recovery.action === "restore") {
        this.applyOrderState(recovery.state, {
          phase: "draw",
          spinning: true,
          wheelInitialResult: null
        });
        const wheel = this.wheel();
        if (wheel) {
          wheel.spinTo(recovery.result);
        } else {
          this.handleWheelFinish({ detail: { result: recovery.result } });
        }
        return;
      }
      if (latest && challengeInvalid(latest)) {
        this.applyOrderState(latest, {
          loadError: latest.eligible && campaignEnabled(latest)
            ? "分享凭证已失效，请重新从右上角分享"
            : stateMessage(latest)
        });
        return;
      }
      if (latest && !latest.shareTriggered) {
        this.applyOrderState(latest, {
          statusText: "分享资格已变化，请重新从右上角进入朋友圈分享"
        });
        this._shareCallbackObserved = false;
        return;
      }
      wx.showModal({
        title: recovery.title || "抽奖资格仍在",
        content: latest
          ? "本次没有生成中奖结果，资格未消耗，可检查网络后重试。"
          : `${(error && error.message) || "网络连接失败"}。请保留订单，重新进入后会先恢复服务端结果。`,
        showCancel: false
      });
    } finally {
      this.setData({ drawLoading: false });
    }
  },

  handleWheelFinish(event) {
    const result = event && event.detail && event.detail.result;
    this.setData({ spinning: false });
    if (!result) {
      return;
    }
    this.applyOrderState({
      campaign: this.data.campaign,
      prizes: this.data.prizes,
      challengeToken: this.data.challengeToken,
      shareTriggered: true,
      drawn: true,
      result
    });
  },

  handleContinuePay() {
    if (this.data.phase !== "result" || this.data.continueLoading || this.data.spinning) {
      return;
    }
    this.returnToPayment("continue");
  },

  handleSkipPay() {
    if (
      this.data.landingMode
      || this.data.reporting
      || this.data.drawLoading
      || this.data.spinning
      || this.data.phase === "result"
    ) {
      return;
    }
    this.returnToPayment("skip");
  },

  handleRetry() {
    if (this.data.landingMode) {
      this.loadLandingContent();
      return;
    }
    this._shareCallbackObserved = false;
    this.loadOrderWheel({
      orderId: this.data.orderId,
      source: this.data.source
    });
  },

  returnToPayment(action) {
    if (this._returned || this.data.landingMode) {
      return;
    }
    this._returned = true;
    this.setData({ continueLoading: action === "continue" });
    updateLotterySession({
      action,
      challengeToken: this.data.challengeToken
    });
    wx.navigateBack({
      delta: 1,
      fail: () => {
        updateLotterySession({ source: "order-detail" });
        wx.redirectTo({
          url: `/pages/order-detail/index?id=${this.data.orderId}`
        });
      }
    });
  }
});
