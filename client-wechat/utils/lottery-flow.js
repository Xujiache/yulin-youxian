const {
  createLotteryChallenge,
  getOrderLottery
} = require("../api/marketing");
const {
  clearLotterySession,
  writeLotterySession
} = require("./lottery-session");
const {
  isChallengeInvalid,
  lotteryCampaignEnabled
} = require("./lottery-status");

function mergeLotteryState(base, extra) {
  const current = base || {};
  const next = extra || {};
  return {
    ...current,
    ...next,
    campaign: next.campaign || current.campaign,
    prizes: next.prizes && next.prizes.length ? next.prizes : current.prizes,
    result: next.result || current.result
  };
}

function shouldOpenLotteryPage(state) {
  if (!state) {
    return false;
  }
  if (state.drawn) {
    return Boolean(state.result);
  }
  if (state.shareTriggered) {
    return true;
  }
  return Boolean(state.eligible && lotteryCampaignEnabled(state));
}

function shouldResumePayment(action) {
  return action === "skip" || action === "continue";
}

function sessionActionForState(state) {
  if (state && state.drawn) {
    return "drawn";
  }
  if (state && state.shareTriggered) {
    return "shared";
  }
  return "pending";
}

function navigateToLotteryPage(orderId, source) {
  return new Promise((resolve, reject) => {
    wx.navigateTo({
      url: `/pages/lucky-activity/index?orderId=${Number(orderId)}&source=${source}`,
      success: () => resolve(true),
      fail: (error) => reject(error || new Error("转盘页打开失败"))
    });
  });
}

async function prepareLotteryPageState(orderId, state) {
  let next = state || {};
  if (
    !next.drawn
    && !next.shareTriggered
    && (!next.challengeToken || isChallengeInvalid(next))
  ) {
    const challenged = await createLotteryChallenge(orderId);
    next = mergeLotteryState(next, challenged);
  }
  const latest = await getOrderLottery(orderId);
  return mergeLotteryState(next, latest);
}

async function openLotteryPage({
  orderId,
  source,
  paymentMethod,
  state
}) {
  if (state && state.drawn && !state.result) {
    return { opened: false, reason: "RESULT_PENDING", state };
  }
  if (!shouldOpenLotteryPage(state)) {
    return { opened: false, reason: "NOT_ELIGIBLE", state: state || {} };
  }
  const next = await prepareLotteryPageState(orderId, state);
  if (next.drawn && !next.result) {
    return { opened: false, reason: "RESULT_PENDING", state: next };
  }
  if (!shouldOpenLotteryPage(next)) {
    return { opened: false, reason: "NOT_ELIGIBLE", state: next };
  }
  writeLotterySession({
    orderId,
    source,
    challengeToken: next.challengeToken || "",
    paymentMethod,
    action: sessionActionForState(next)
  });
  try {
    await navigateToLotteryPage(orderId, source);
    return { opened: true, state: next };
  } catch (error) {
    clearLotterySession(orderId, source);
    throw error;
  }
}

module.exports = {
  mergeLotteryState,
  openLotteryPage,
  prepareLotteryPageState,
  sessionActionForState,
  shouldOpenLotteryPage,
  shouldResumePayment
};
