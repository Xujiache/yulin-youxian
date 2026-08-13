const assert = require("node:assert/strict");
const test = require("node:test");

// api/normalize 的资源归一化依赖小程序的 getApp()
global.getApp = () => ({ globalData: { apiBaseUrl: "https://api.test" } });

const {
  markerAnimationDuration,
  normalizeTracking,
  pollingIntervalMs,
  shouldContinueUnavailableTracking,
  trackingPollDelayMs
} = require("../pages/order-detail/tracking");
const { homeNavigationPlan } = require("../utils/navigation");
const { normalizeLotteryState } = require("../api/marketing");
const { normalizeOrderDetail } = require("../api/orders");
const {
  campaignNotice,
  campaignWindowState,
  hasActivePaymentShare,
  isChallengeInvalid,
  lotteryReasonCode,
  lotteryReasonText
} = require("../utils/lottery-status");
const {
  inspectPaymentShare,
  paymentShareReuseState
} = require("../utils/payment-share-store");
const {
  pendingOrderDecision,
  pendingOrderSignature
} = require("../pages/checkout/pending-order");
const { resolvePrizeIndex, targetRotation } = require("../components/lucky-wheel/wheel-math");
const {
  isPaidOrder,
  paymentShareConfirmationState,
  waitForPaymentShareResult
} = require("../utils/wechat-payment");

test("tracking keeps probing a delivering order without a delivery task", () => {
  const raw = {
    hasDelivery: false,
    polling: { intervalSeconds: 7 }
  };

  assert.equal(normalizeTracking(raw).available, false);
  assert.equal(pollingIntervalMs(raw), 7000);
  assert.equal(shouldContinueUnavailableTracking("配送中", raw), true);
  assert.equal(trackingPollDelayMs("配送中", raw, 5000, 15000), 15000);
  assert.equal(shouldContinueUnavailableTracking("已完成", raw), false);
  assert.equal(trackingPollDelayMs("已完成", raw, 5000, 15000), 7000);
  assert.equal(shouldContinueUnavailableTracking("配送中", {}), false);
});

test("tracking honors polling bounds and avoids overlapping marker duration", () => {
  assert.equal(pollingIntervalMs({ polling: { intervalSeconds: 0.1 } }), 1000);
  assert.equal(pollingIntervalMs({ polling: { intervalSeconds: 120 } }), 60000);
  assert.equal(markerAnimationDuration(5000, 4800), 4800);
  assert.equal(markerAnimationDuration(2000, 4800), 1800);
});

test("tracking exposes degraded phone warning with transition fields", () => {
  const delivery = normalizeTracking({
    hasDelivery: true,
    taskStatus: "DELIVERING",
    degraded: true,
    notice: { message: "隐私号服务暂时降级" },
    rider: {
      name: "张师傅",
      callNumber: "13800000000",
      location: { lat: 30.12, lng: 120.76 }
    },
    destination: { lat: 30.13, lng: 120.77 }
  });

  assert.equal(delivery.rider.phoneDegraded, true);
  assert.equal(delivery.noticeText, "隐私号服务暂时降级");
  assert.equal(delivery.phoneWarningText, "隐私号服务暂时降级");
});

test("payment confirmation recognizes paid status and invalidated token", async () => {
  assert.equal(paymentShareConfirmationState({ share: { paymentStatus: "SUCCESS" } }), "CONFIRMED");
  assert.equal(paymentShareConfirmationState({ share: { payableAmount: 100 } }), "PENDING");
  assert.equal(paymentShareConfirmationState({ error: { code: 404 } }), "CONFIRMED");
  assert.equal(isPaidOrder({ status: "配送中", paidAmount: 0 }), true);

  let calls = 0;
  const result = await waitForPaymentShareResult("token", {
    attempts: 2,
    interval: 1,
    fetchShare: async () => {
      calls += 1;
      if (calls === 1) {
        return { payableAmount: 100 };
      }
      const error = new Error("付款链接已失效");
      error.code = 404;
      throw error;
    }
  });

  assert.equal(result.confirmed, true);
  assert.equal(calls, 2);
});

test("home navigation uses non-tab routes with a relaunch fallback", () => {
  assert.deepEqual(homeNavigationPlan(), [
    { method: "redirectTo", url: "/pages/home/index" },
    { method: "reLaunch", url: "/pages/home/index" }
  ]);
  assert.equal(homeNavigationPlan().some((step) => step.method === "switchTab"), false);
});

test("lottery response keeps the server result and sorts visible prizes", () => {
  const state = normalizeLotteryState({
    eligible: true,
    challengeToken: "challenge",
    shareTriggered: true,
    prizes: [
      { id: 2, type: "NONE", name: "谢谢惠顾", sortOrder: 20 },
      { id: 1, type: "DISCOUNT", name: "减 1 元", sortOrder: 10 }
    ],
    result: {
      drawId: 9,
      prizeId: 1,
      prizeIndex: 0,
      prizeType: "DISCOUNT",
      prizeName: "减 1 元",
      discountAmount: 100,
      payableAmount: 1298
    }
  });

  assert.equal(state.drawn, true);
  assert.deepEqual(state.prizes.map((prize) => prize.id), [1, 2]);
  assert.equal(state.result.discountAmount, 100);
  assert.equal(state.result.payableAmount, 1298);
});

test("checkout drops the pending order whenever the order inputs change", () => {
  const base = { addressId: 3, deliverySlotId: 7, remark: "放门口", cartItemIds: [2, 1] };
  const signature = pendingOrderSignature(base);

  assert.equal(pendingOrderSignature({ ...base, cartItemIds: [1, 2] }), signature);
  assert.notEqual(pendingOrderSignature({ ...base, addressId: 4 }), signature);
  assert.notEqual(pendingOrderSignature({ ...base, deliverySlotId: 8 }), signature);
  assert.notEqual(pendingOrderSignature({ ...base, remark: "" }), signature);
  assert.notEqual(pendingOrderSignature({ ...base, cartItemIds: [1] }), signature);
  assert.notEqual(
    pendingOrderSignature({ ...base, buyNowRequest: { productId: 5, quantity: 1 } }),
    signature
  );

  const moved = pendingOrderSignature({ ...base, addressId: 4 });
  assert.equal(
    pendingOrderDecision({ pendingOrderId: 0, signature, pendingSignature: "" }).action,
    "create"
  );
  assert.equal(
    pendingOrderDecision({ pendingOrderId: 9, signature, pendingSignature: signature }).action,
    "reuse"
  );
  assert.equal(
    pendingOrderDecision({ pendingOrderId: 9, signature: moved, pendingSignature: signature }).action,
    "recreate"
  );
  assert.deepEqual(
    pendingOrderDecision({
      pendingOrderId: 9,
      signature: moved,
      pendingSignature: signature,
      lotteryState: { drawn: true, result: { prizeId: 1 } }
    }),
    { action: "confirm", lockKind: "DRAWN" }
  );
  assert.deepEqual(
    pendingOrderDecision({
      pendingOrderId: 9,
      signature: moved,
      pendingSignature: signature,
      lotteryState: { shareTriggered: true }
    }),
    { action: "confirm", lockKind: "SHARED" }
  );
});

test("lottery gating reads reasonCode and degrades to the chinese reason", () => {
  assert.equal(
    lotteryReasonCode({ reasonCode: "ACTIVE_PAYMENT_SHARE", reason: "已有有效好友代付链接，不能再抽奖" }),
    "ACTIVE_PAYMENT_SHARE"
  );
  assert.equal(hasActivePaymentShare({ reasonCode: "ACTIVE_PAYMENT_SHARE" }), true);
  assert.equal(hasActivePaymentShare({ reason: "已有有效好友代付链接，不能再抽奖" }), true);
  assert.equal(hasActivePaymentShare({ reason: "今日抽奖次数已用完" }), false);

  assert.equal(lotteryReasonCode({ reason: "该订单已抽奖" }), "ALREADY_DRAWN");
  assert.equal(lotteryReasonCode({ reason: "订单已跳过抽奖并锁定价格" }), "ORDER_SKIPPED");
  assert.equal(lotteryReasonCode({ reason: "仅待支付订单可以抽奖" }), "ORDER_NOT_PENDING");
  assert.equal(lotteryReasonCode({ reason: "抽奖活动未开启" }), "CAMPAIGN_DISABLED");
  assert.equal(lotteryReasonCode({ reason: "抽奖活动尚未开始" }), "CAMPAIGN_NOT_STARTED");
  assert.equal(lotteryReasonCode({ reason: "抽奖活动已结束" }), "CAMPAIGN_ENDED");
  assert.equal(lotteryReasonCode({ reason: "今日抽奖次数已用完" }), "DAILY_LIMIT_REACHED");
  assert.equal(lotteryReasonCode({ reason: "订单商品金额不在活动阶梯内" }), "TIER_NOT_MATCHED");
  assert.equal(lotteryReasonCode({ reason: "当前阶梯暂无可抽取奖项" }), "NO_AVAILABLE_PRIZE");

  assert.equal(isChallengeInvalid({ reasonCode: "CHALLENGE_EXPIRED" }), true);
  assert.equal(isChallengeInvalid({ reason: "CHALLENGE_INVALID" }), true);
  assert.equal(isChallengeInvalid({ reason: "分享凭证已失效" }), true);
  assert.equal(isChallengeInvalid({}), false);
  assert.equal(isChallengeInvalid({ reason: "今日抽奖次数已用完" }), false);

  assert.equal(lotteryReasonCode({}), "");
  assert.equal(lotteryReasonText({ reasonCode: "DAILY_LIMIT_REACHED" }), "今日抽奖次数已用完");
  assert.equal(lotteryReasonText({ reason: "服务端临时限流" }), "服务端临时限流");
  assert.equal(lotteryReasonText({}, "本单暂不能参与"), "本单暂不能参与");
});

test("landing page hides the prize list outside the campaign window", () => {
  const campaign = { enabled: true, startAt: "2026-08-20T10:00:00", endAt: "2026-08-30T22:00:00" };

  assert.equal(campaignWindowState(campaign, new Date(2026, 7, 19, 9)), "NOT_STARTED");
  assert.equal(campaignWindowState(campaign, new Date(2026, 7, 20, 10)), "ACTIVE");
  assert.equal(campaignWindowState(campaign, new Date(2026, 7, 30, 22)), "ENDED");
  assert.equal(campaignWindowState({ enabled: false }, new Date()), "DISABLED");
  assert.equal(campaignWindowState({ enabled: true }, new Date()), "ACTIVE");

  assert.equal(campaignNotice(campaign, new Date(2026, 7, 21, 12)), null);
  assert.equal(campaignNotice(campaign, new Date(2026, 7, 19, 9)).status, "NOT_STARTED");
  assert.match(campaignNotice(campaign, new Date(2026, 7, 19, 9)).desc, /8月20日 10:00/);
  assert.equal(campaignNotice(campaign, new Date(2026, 8, 1)).status, "ENDED");
  assert.equal(campaignNotice(null).status, "DISABLED");
});

test("wheel never lands on a wrong slot when the prize index is unusable", () => {
  const prizes = [{ id: 1 }, { id: 2 }, { id: 3 }];

  assert.equal(resolvePrizeIndex(prizes, { prizeId: 3, prizeIndex: -1 }), 2);
  assert.equal(resolvePrizeIndex(prizes, { prizeId: 99, prizeIndex: 1 }), 1);
  assert.equal(resolvePrizeIndex(prizes, { prizeId: 99, prizeIndex: -1 }), -1);
  assert.equal(resolvePrizeIndex(prizes, { prizeIndex: 7 }), -1);
  assert.equal(resolvePrizeIndex([], { prizeId: 1, prizeIndex: 0 }), -1);

  assert.equal(targetRotation({ index: 1, count: 4, currentRotation: 0 }), 270);
  assert.equal(targetRotation({ index: 1, count: 4, currentRotation: 0, withTurns: true }), 2070);
  assert.equal(targetRotation({ index: -1, count: 4, currentRotation: 240, withTurns: true }), 240);
  assert.equal(targetRotation({ index: 9, count: 4, currentRotation: 240 }), 240);
});

test("friend payment reuses a live share instead of rotating the token", async () => {
  assert.equal(paymentShareReuseState({ share: { payableAmount: 100 } }), "REUSABLE");
  assert.equal(paymentShareReuseState({ share: { paymentStatus: "SUCCESS" } }), "PAID");
  assert.equal(paymentShareReuseState({ error: { code: 404 } }), "EXPIRED");
  assert.equal(paymentShareReuseState({ error: { statusCode: 410 } }), "EXPIRED");
  assert.equal(paymentShareReuseState({ error: { code: "NETWORK_ERROR" } }), "UNKNOWN");

  // 读不到本机记录时必须安静退化成「没有可复用链接」，不能抛错挡住付款
  assert.deepEqual(await inspectPaymentShare(101, async () => ({ payableAmount: 1 })), {
    token: "",
    state: "NONE"
  });
});

// 后端的业务错误是 HTTP 200 + body.code，如果只保留 statusCode，
// 代付链接失效（404）的分支永远走不到，会退化成「无法确认」的二次确认弹窗
test("request surfaces the business code carried in the response body", async () => {
  const previousWx = global.wx;
  global.wx = {
    getStorageSync: () => "",
    request({ success }) {
      success({ statusCode: 200, data: { code: 404, message: "付款链接已失效" } });
    }
  };
  try {
    const request = require("../utils/request");
    await assert.rejects(
      request({ url: "/api/public/payment-shares/expired", skipAuth: true, retry: false }),
      (error) => {
        assert.equal(error.code, 404);
        assert.equal(error.statusCode, 200);
        assert.equal(paymentShareReuseState({ error }), "EXPIRED");
        assert.equal(paymentShareConfirmationState({ error }), "CONFIRMED");
        return true;
      }
    );
  } finally {
    global.wx = previousWx;
  }
});

test("order detail turns relative gift images into absolute urls", () => {
  const detail = normalizeOrderDetail({
    items: [],
    gifts: [{ drawId: 1, productName: "有机菠菜", imageUrl: "/assets/products/spinach.png" }],
    lotteryResult: {
      prizeId: 3,
      imageUrl: "/assets/products/carrot.png",
      gifts: [{ imageUrl: "/assets/products/tomato.png" }]
    }
  });

  assert.equal(detail.gifts[0].imageUrl, "https://api.test/assets/products/spinach.png");
  assert.equal(detail.lotteryResult.imageUrl, "https://api.test/assets/products/carrot.png");
  assert.equal(detail.lotteryResult.gifts[0].imageUrl, "https://api.test/assets/products/tomato.png");
  assert.deepEqual(normalizeOrderDetail({}).gifts, []);
});
