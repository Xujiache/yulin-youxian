const request = require("../utils/request");
const { normalizeAssetUrl, normalizePrizeResult } = require("./normalize");

function numberOr(value, fallback = 0) {
  const number = Number(value);
  return Number.isFinite(number) ? number : fallback;
}

const DEFAULT_SORT_ORDER = 100;
const FIXED_PRIZE_CODES = ["FIRST", "SECOND", "THIRD", "NONE"];
const PRIZE_CODE_ORDER = FIXED_PRIZE_CODES.reduce((order, code, index) => {
  order[code] = index;
  return order;
}, {});

function normalizePrizeCode(value) {
  const code = String(value || "").trim().toUpperCase();
  return Object.prototype.hasOwnProperty.call(PRIZE_CODE_ORDER, code) ? code : "";
}

function normalizePrize(prize = {}) {
  return {
    id: prize.id,
    type: prize.type || "",
    name: prize.name || "鲜礼",
    prizeCode: normalizePrizeCode(prize.prizeCode),
    imageUrl: normalizeAssetUrl(prize.imageUrl || ""),
    sortOrder: numberOr(prize.sortOrder, DEFAULT_SORT_ORDER),
    productId: prize.productId,
    skuId: prize.skuId
  };
}

function comparePrizes(left, right) {
  const leftCode = PRIZE_CODE_ORDER[left.prizeCode];
  const rightCode = PRIZE_CODE_ORDER[right.prizeCode];
  if (leftCode !== undefined && rightCode !== undefined) {
    return leftCode - rightCode;
  }
  if (leftCode !== undefined) {
    return -1;
  }
  if (rightCode !== undefined) {
    return 1;
  }
  const bySortOrder = left.sortOrder - right.sortOrder;
  if (bySortOrder !== 0) {
    return bySortOrder;
  }
  return numberOr(left.id, Number.MAX_SAFE_INTEGER) - numberOr(right.id, Number.MAX_SAFE_INTEGER);
}

function normalizePrizes(prizes) {
  return (Array.isArray(prizes) ? prizes : [])
    .map(normalizePrize)
    .sort(comparePrizes);
}

function normalizeDrawResult(result) {
  if (!result) {
    return null;
  }
  return {
    ...normalizePrizeResult(result),
    prizeCode: normalizePrizeCode(result.prizeCode),
    prizeIndex: numberOr(result.prizeIndex, -1),
    discountAmount: numberOr(result.discountAmount),
    payableAmount: numberOr(result.payableAmount)
  };
}

function normalizeCampaign(campaign) {
  if (!campaign) {
    return null;
  }
  return {
    ...campaign,
    enabled: campaign.enabled !== false,
    shareImageUrl: normalizeAssetUrl(campaign.shareImageUrl || ""),
    prizes: normalizePrizes(campaign.prizes),
    tiers: Array.isArray(campaign.tiers) ? campaign.tiers.slice() : []
  };
}

function looksLikeCampaign(payload) {
  return Boolean(
    payload
    && payload.id !== undefined
    && (
      payload.enabled !== undefined
      || payload.shareTitle
      || payload.prizes
      || payload.tiers
    )
  );
}

function normalizeLotteryState(payload = {}) {
  const campaignSource = payload.campaign || (looksLikeCampaign(payload) ? payload : null);
  const campaign = normalizeCampaign(campaignSource);
  const prizes = normalizePrizes(
    payload.prizes
    || (campaignSource && campaignSource.prizes)
    || []
  );
  const result = normalizeDrawResult(payload.result || (payload.drawId ? payload : null));
  return {
    ...payload,
    eligible: Boolean(payload.eligible),
    reason: payload.reason || "",
    reasonCode: String(payload.reasonCode || "").trim().toUpperCase(),
    challengeToken: payload.challengeToken || "",
    shareTriggered: Boolean(payload.shareTriggered),
    drawn: Boolean(payload.drawn || result),
    campaign,
    prizes,
    result
  };
}

function recoverLotteryDrawAfterFailure({ getState, getFailed }) {
  if (getState && getState.drawn && getState.result) {
    return {
      action: "restore",
      state: getState,
      result: getState.result
    };
  }
  return {
    action: "keep-eligibility",
    title: getFailed ? "抽奖结果暂未确认" : "抽奖资格仍在"
  };
}

async function getPublicLottery() {
  const payload = await request({
    url: "/api/public/marketing/lottery",
    skipAuth: true
  });
  return normalizeLotteryState(payload || {});
}

async function getOrderLottery(orderId) {
  const payload = await request({
    url: `/api/wx/orders/${orderId}/lottery`
  });
  return normalizeLotteryState(payload || {});
}

async function createLotteryChallenge(orderId) {
  const payload = await request({
    url: `/api/wx/orders/${orderId}/lottery/challenge`,
    method: "POST"
  });
  return normalizeLotteryState(payload || {});
}

async function reportLotteryShareTrigger(orderId, challengeToken) {
  const payload = await request({
    url: `/api/wx/orders/${orderId}/lottery/share-trigger`,
    method: "POST",
    data: { challengeToken }
  });
  return normalizeLotteryState(payload || {});
}

async function drawLottery(orderId, challengeToken) {
  const payload = await request({
    url: `/api/wx/orders/${orderId}/lottery/draw`,
    method: "POST",
    data: { challengeToken }
  });
  const state = normalizeLotteryState(payload || {});
  return {
    state,
    result: state.result || normalizeDrawResult(payload)
  };
}

module.exports = {
  FIXED_PRIZE_CODES,
  createLotteryChallenge,
  drawLottery,
  getOrderLottery,
  getPublicLottery,
  normalizeDrawResult,
  normalizeLotteryState,
  normalizePrizeCode,
  recoverLotteryDrawAfterFailure,
  reportLotteryShareTrigger
};
