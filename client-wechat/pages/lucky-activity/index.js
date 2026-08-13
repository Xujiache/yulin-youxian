const {
  getOrderLottery,
  getPublicLottery,
  reportLotteryShareTrigger
} = require("../../api/marketing");
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

Page({
  data: {
    landingMode: false,
    loading: true,
    loadError: "",
    campaign: {},
    prizes: [],
    campaignEnabled: true,
    campaignNotice: null,
    orderId: 0,
    source: "",
    challengeToken: "",
    shareReady: false,
    shareMenuAvailable: true,
    reporting: false,
    shareReported: false,
    statusText: ""
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
    this.setData({ landingMode });
    this.enableTimelineMenu();
    if (landingMode) {
      this.loadLandingContent();
      return;
    }
    this.loadNormalActivity(options);
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
      !this.data.shareReady
      || !this.data.orderId
      || !this.data.challengeToken
      || this.data.reporting
    ) {
      // 帖子照样会发出去，但资格没记录；静默返回会让用户白发一条朋友圈
      const hint = this.data.reporting
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
      if (
        this._returned
        || !report.ok
        || !report.state
        || (!report.state.shareTriggered && !report.state.drawn)
      ) {
        return;
      }
      const challengeToken = report.state.challengeToken || this.data.challengeToken;
      updateLotterySession({ action: "shared", challengeToken });
      this.setData({
        reporting: false,
        shareReported: true,
        challengeToken,
        statusText: "分享入口已记录，关闭面板后将自动返回"
      });
    });
    return shareData;
  },

  // 微信要求页面先声明“发送给朋友”，右上角才会展示“分享到朋友圈”。
  // 好友分享只打开活动展示页，不授予本订单抽奖资格。
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

  async loadLandingContent() {
    this.setData({ loading: true, loadError: "" });
    try {
      const state = await getPublicLottery();
      // 服务端直接下发活动配置、不做时间窗过滤，落地页必须自己判断 startAt/endAt，
      // 否则未开始或已结束时访客照样看到完整奖品列表。
      const notice = campaignNotice(state.campaign);
      this.setData({
        campaign: state.campaign || {},
        prizes: state.prizes || [],
        campaignEnabled: !notice,
        campaignNotice: notice,
        loadError: ""
      });
    } catch (error) {
      this.setData({
        campaignEnabled: false,
        campaignNotice: null,
        loadError: (error && error.message) || "活动信息暂时没有加载出来"
      });
    } finally {
      this.setData({ loading: false });
    }
  },

  async loadNormalActivity(options = {}) {
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
      const state = await getOrderLottery(orderId);
      if (state.drawn || state.shareTriggered) {
        this.setData({
          campaign: state.campaign || {},
          prizes: state.prizes || [],
          shareReported: true,
          statusText: "资格已经记录，正在返回付款页"
        });
        updateLotterySession({
          action: "shared",
          challengeToken: state.challengeToken || session.challengeToken || ""
        });
        setTimeout(() => this.returnToPayment("shared"), 280);
        return;
      }
      if (!state.eligible || !campaignEnabled(state)) {
        this.setData({
          campaign: state.campaign || {},
          prizes: state.prizes || [],
          campaignEnabled: campaignEnabled(state),
          loadError: stateMessage(state, "本单暂不符合活动条件")
        });
        return;
      }
      const challengeToken = state.challengeToken || session.challengeToken || "";
      if (!challengeToken || challengeInvalid(state)) {
        this.setData({
          campaign: state.campaign || {},
          prizes: state.prizes || [],
          loadError: "分享凭证已失效，请返回付款页重新进入"
        });
        return;
      }
      updateLotterySession({ challengeToken, action: "pending" });
      this.setData({
        campaign: state.campaign || {},
        prizes: state.prizes || [],
        campaignEnabled: true,
        challengeToken,
        shareReady: true,
        statusText: ""
      });
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
        const challengeToken = state.challengeToken || this.data.challengeToken;
        updateLotterySession({ action: "shared", challengeToken });
        this.setData({
          reporting: false,
          shareReported: true,
          challengeToken,
          statusText: "分享入口已记录，正在返回抽奖"
        });
        this.returnToPayment("shared");
        return;
      }
      if (state && state.eligible && campaignEnabled(state) && !challengeInvalid(state)) {
        const challengeToken = state.challengeToken || this.data.challengeToken;
        updateLotterySession({ action: "pending", challengeToken });
        this.setData({
          reporting: false,
          challengeToken,
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

  handleRetry() {
    if (this.data.landingMode) {
      this.loadLandingContent();
      return;
    }
    this._shareCallbackObserved = false;
    this.loadNormalActivity({
      orderId: this.data.orderId,
      source: this.data.source
    });
  },

  handleOriginalPay() {
    if (this.data.reporting) {
      return;
    }
    this.returnToPayment("skip");
  },

  handleBackToPayment() {
    if (this.data.reporting) {
      return;
    }
    this.returnToPayment("pending");
  },

  handleReturnAfterShare() {
    if (!this.data.shareReported) {
      return;
    }
    this.returnToPayment("shared");
  },

  returnToPayment(action) {
    if (this._returned || this.data.landingMode) {
      return;
    }
    this._returned = true;
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
