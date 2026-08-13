const request = require("../utils/request");
const { normalizeAssetUrl, normalizePrizeResult } = require("./normalize");

function numberOr(value, fallback = 0) {
  const number = Number(value);
  return Number.isFinite(number) ? number : fallback;
}

// 后端下发的列表已经排好序，缺省 sortOrder 按 100 处理、并列时按 id 排。
// 本地排序必须用同一套规则，否则转盘的下标空间会和 prizeIndex 错开。
const DEFAULT_SORT_ORDER = 100;

function normalizePrize(prize = {}) {
  return {
    ...prize,
    id: prize.id,
    type: prize.type || "",
    name: prize.name || "鲜礼",
    imageUrl: normalizeAssetUrl(prize.imageUrl || ""),
    sortOrder: numberOr(prize.sortOrder, DEFAULT_SORT_ORDER)
  };
}

function comparePrizes(left, right) {
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
    // 稳定枚举，判定统一按它走；旧服务端不下发时保持空串，由 utils/lottery-status 从中文 reason 兜底
    reasonCode: String(payload.reasonCode || "").trim().toUpperCase(),
    challengeToken: payload.challengeToken || "",
    shareTriggered: Boolean(payload.shareTriggered),
    drawn: Boolean(payload.drawn || result),
    campaign,
    prizes,
    result
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
  createLotteryChallenge,
  drawLottery,
  getOrderLottery,
  getPublicLottery,
  normalizeDrawResult,
  normalizeLotteryState,
  reportLotteryShareTrigger
};
