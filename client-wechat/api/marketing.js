const request = require("../utils/request");
const { normalizeAssetUrl } = require("./normalize");

function numberOr(value, fallback = 0) {
  const number = Number(value);
  return Number.isFinite(number) ? number : fallback;
}

function normalizePrize(prize = {}) {
  return {
    ...prize,
    id: prize.id,
    type: prize.type || "",
    name: prize.name || "鲜礼",
    imageUrl: normalizeAssetUrl(prize.imageUrl || ""),
    sortOrder: numberOr(prize.sortOrder)
  };
}

function normalizePrizes(prizes) {
  return (Array.isArray(prizes) ? prizes : [])
    .map(normalizePrize)
    .sort((left, right) => left.sortOrder - right.sortOrder);
}

function normalizeGift(gift = {}) {
  return {
    ...gift,
    imageUrl: normalizeAssetUrl(gift.imageUrl || "")
  };
}

function normalizeDrawResult(result) {
  if (!result) {
    return null;
  }
  return {
    ...result,
    prizeIndex: numberOr(result.prizeIndex, -1),
    discountAmount: numberOr(result.discountAmount),
    payableAmount: numberOr(result.payableAmount),
    imageUrl: normalizeAssetUrl(result.imageUrl || ""),
    gifts: (Array.isArray(result.gifts) ? result.gifts : []).map(normalizeGift)
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
