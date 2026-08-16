const assert = require("node:assert/strict");
const test = require("node:test");

// api/normalize 的资源归一化依赖小程序的 getApp()
global.getApp = () => ({ globalData: { apiBaseUrl: "https://api.test" } });

const {
  markerAnimationDuration,
  normalizeTracking,
  pollingIntervalMs,
  shouldContinueUnavailableTracking,
  trackingPollDelayMs,
  isActiveDeliveryOrder,
  dedupeTrailPoints,
  remainingRoutePoints,
  buildMapPolylines
} = require("../pages/order-detail/tracking");
const { normalizeAssetUrl } = require("../api/normalize");
const { homeNavigationPlan } = require("../utils/navigation");
const { normalizeLotteryState, recoverLotteryDrawAfterFailure } = require("../api/marketing");
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
const {
  resolvePrizeIndex,
  slotCenterAngle,
  slotTargetAngle,
  targetRotation
} = require("../components/lucky-wheel/wheel-math");
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
  assert.equal(shouldContinueUnavailableTracking("备货中", raw), true);
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

test("active orders keep polling through preparing and delivering", () => {
  assert.equal(isActiveDeliveryOrder("备货中"), true);
  assert.equal(isActiveDeliveryOrder("配送中"), true);
  assert.equal(isActiveDeliveryOrder("已完成"), false);
  assert.equal(isActiveDeliveryOrder("待支付"), false);
});

test("tracking shows queue and eta without a live rider point", () => {
  const delivery = normalizeTracking({
    hasDelivery: true,
    taskStatus: "PICKED_UP",
    taskStatusText: "骑手已取货",
    stopsAhead: 2,
    eta: { remainingSeconds: 180, displayText: "预计 15:45-15:55 送达" },
    rider: { name: "张师傅" },
    destination: { lat: 30.13, lng: 120.77 }
  });

  assert.equal(delivery.stopsAheadText, "骑手还有 2 单送达你");
  assert.equal(delivery.remainingText, "约需 3 分钟");
  assert.equal(delivery.waitingText, "骑手已取货，正在确认发车");
  assert.equal(delivery.showMap, false);
  assert.equal(delivery.staleText, "");
});

test("tracking keeps a stale rider frame after GPS drops", () => {
  const delivery = normalizeTracking({
    hasDelivery: true,
    taskStatus: "DELIVERING",
    taskStatusText: "骑手正在配送",
    stopsAhead: 0,
    rider: {
      name: "张师傅",
      location: { lat: 30.12, lng: 120.76 },
      locatedAt: "2026-08-11T15:39:50",
      locationFresh: false
    },
    destination: { lat: 30.13, lng: 120.77 }
  });

  assert.equal(delivery.showMap, true);
  assert.equal(delivery.staleText, "骑手位置更新中");
  assert.equal(delivery.locationUpdatedText, "定位 15:39 更新");
  assert.equal(delivery.stopsAheadText, "你是骑手的下一单");
});

test("tracking trail is deduped and truncated, remaining route falls back to rider-destination", () => {
  const trail = dedupeTrailPoints([
    { lat: 30.12, lng: 120.76, at: "2026-08-11T15:38:00" },
    { lat: 30.12, lng: 120.76, at: "2026-08-11T15:38:05" },
    { lat: 30.121, lng: 120.761 },
    { lat: 30.122, lng: 120.762 }
  ], 2);
  assert.equal(trail.length, 2);
  assert.equal(trail[0].lat, 30.121);
  assert.equal(trail[1].lat, 30.122);

  const fallback = remainingRoutePoints(null, { lat: 30.12, lng: 120.76 }, { lat: 30.13, lng: 120.77 });
  assert.deepEqual(fallback, [
    { lat: 30.12, lng: 120.76 },
    { lat: 30.13, lng: 120.77 }
  ]);
  assert.equal(remainingRoutePoints({ points: [] }, null, { lat: 30.13, lng: 120.77 }).length, 0);

  const lines = buildMapPolylines(
    [{ lat: 30.12, lng: 120.76 }, { lat: 30.121, lng: 120.761 }],
    [{ lat: 30.121, lng: 120.761 }, { lat: 30.13, lng: 120.77 }]
  );
  assert.equal(lines.length, 2);
  assert.equal(lines[0].dottedLine, false);
  assert.equal(lines[1].dottedLine, true);
});

test("terminal tracking stops showing rider, trail and remaining minutes", () => {
  const delivery = normalizeTracking({
    hasDelivery: true,
    taskStatus: "DELIVERED",
    polling: { intervalSeconds: 5, stopWhenDone: true },
    rider: null,
    stopsAhead: 0,
    eta: { remainingSeconds: 0 }
  });
  assert.equal(delivery.terminal, true);
  assert.equal(delivery.showMap, false);
  assert.equal(delivery.stopsAheadText, "");
  assert.equal(delivery.remainingText, "");
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
      { id: 4, type: "NONE", name: "谢谢惠顾", prizeCode: "NONE", probabilityBp: 7000 },
      { id: 2, type: "DISCOUNT", name: "二等奖", prizeCode: "SECOND" },
      { id: 3, type: "DISCOUNT", name: "三等奖", prizeCode: "THIRD" },
      { id: 1, type: "DISCOUNT", name: "一等奖", prizeCode: "FIRST" }
    ],
    result: {
      drawId: 9,
      prizeId: 1,
      prizeCode: "FIRST",
      prizeIndex: 0,
      prizeType: "DISCOUNT",
      prizeName: "一等奖",
      discountAmount: 100,
      payableAmount: 1298
    }
  });

  assert.equal(state.drawn, true);
  assert.deepEqual(state.prizes.map((prize) => prize.prizeCode), ["FIRST", "SECOND", "THIRD", "NONE"]);
  assert.deepEqual(state.prizes.map((prize) => prize.id), [1, 2, 3, 4]);
  assert.equal(state.prizes[0].probabilityBp, undefined);
  assert.equal(state.result.prizeCode, "FIRST");
  assert.equal(state.result.discountAmount, 100);
  assert.equal(state.result.payableAmount, 1298);

  const legacy = normalizeLotteryState({
    prizes: [
      { id: 2, type: "NONE", name: "谢谢惠顾", sortOrder: 20 },
      { id: 1, type: "DISCOUNT", name: "减 1 元", sortOrder: 10 }
    ]
  });
  assert.deepEqual(legacy.prizes.map((prize) => prize.id), [1, 2]);
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
  const prizes = [
    { id: 1, prizeCode: "FIRST" },
    { id: 2, prizeCode: "SECOND" },
    { id: 3, prizeCode: "THIRD" },
    { id: 4, prizeCode: "NONE" }
  ];

  assert.deepEqual([0, 1, 2, 3].map(slotCenterAngle), [0, 90, 180, 270]);
  assert.deepEqual([0, 1, 2, 3].map(slotTargetAngle), [0, 270, 180, 90]);

  assert.equal(resolvePrizeIndex(prizes, { prizeId: 3, prizeCode: "NONE", prizeIndex: 0 }), 2);
  assert.equal(resolvePrizeIndex(prizes, { prizeId: 99, prizeCode: "THIRD", prizeIndex: 0 }), 2);
  assert.equal(resolvePrizeIndex(prizes, { prizeId: 99, prizeIndex: 1 }), 1);
  assert.equal(resolvePrizeIndex(prizes, { prizeId: 99, prizeIndex: -1 }), -1);
  assert.equal(resolvePrizeIndex(prizes, { prizeIndex: 7 }), -1);
  assert.equal(resolvePrizeIndex([], { prizeId: 1, prizeIndex: 0 }), -1);

  assert.equal(targetRotation({ index: 0, count: 4, currentRotation: 0 }), 0);
  assert.equal(targetRotation({ index: 1, count: 4, currentRotation: 0 }), 270);
  assert.equal(targetRotation({ index: 2, count: 4, currentRotation: 0 }), 180);
  assert.equal(targetRotation({ index: 3, count: 4, currentRotation: 0 }), 90);
  assert.equal(targetRotation({ index: 1, count: 4, currentRotation: 0, withTurns: true }), 2430);
  assert.equal(targetRotation({ index: -1, count: 4, currentRotation: 240, withTurns: true }), 240);
  assert.equal(targetRotation({ index: 9, count: 4, currentRotation: 240 }), 240);
});

test("draw recovery prefers the GET result and keeps eligibility when both fail", () => {
  const prizes = [
    { id: 11, prizeCode: "FIRST", name: "一等奖" },
    { id: 12, prizeCode: "SECOND", name: "二等奖" },
    { id: 13, prizeCode: "THIRD", name: "三等奖" },
    { id: 14, prizeCode: "NONE", name: "谢谢惠顾" }
  ];
  const recovered = normalizeLotteryState({
    drawn: true,
    prizes,
    result: {
      drawId: 88,
      prizeId: 13,
      prizeCode: "THIRD",
      prizeIndex: 2,
      discountAmount: 800,
      payableAmount: 4200
    }
  });
  const restored = recoverLotteryDrawAfterFailure({ getState: recovered, getFailed: false });
  assert.equal(restored.action, "restore");
  assert.equal(resolvePrizeIndex(recovered.prizes, recovered.result), 2);
  assert.equal(targetRotation({ index: 2, count: 4 }), 180);

  const keepWhenGetFails = recoverLotteryDrawAfterFailure({ getState: null, getFailed: true });
  assert.equal(keepWhenGetFails.action, "keep-eligibility");
  assert.equal(keepWhenGetFails.title, "抽奖结果暂未确认");

  const keepWhenNoDraw = recoverLotteryDrawAfterFailure({
    getState: normalizeLotteryState({
      eligible: true,
      shareTriggered: true,
      drawn: false,
      prizes,
      reasonCode: "ELIGIBLE"
    }),
    getFailed: false
  });
  assert.equal(keepWhenNoDraw.action, "keep-eligibility");
  assert.equal(keepWhenNoDraw.title, "抽奖资格仍在");
  assert.deepEqual(prizes.map((prize) => prize.prizeCode), ["FIRST", "SECOND", "THIRD", "NONE"]);
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

test("delivery evidence photos keep their signature after normalization", () => {
  const signed = "/uploads/delivery/202608/proof.jpg?e=1786000000&s=Ab-_9xYz";
  const delivery = normalizeTracking({
    hasDelivery: true,
    taskStatus: "DELIVERED",
    deliveryPhotos: [signed, "  " + signed + "  ", "", null]
  });

  // 签名和有效期必须一字不差地留在 src 上，否则拦截器只会回 401，页面就是一排破图
  assert.deepEqual(delivery.deliveryPhotos, [
    `https://api.test${signed}`,
    `https://api.test${signed}`
  ]);
  assert.equal(normalizeAssetUrl(signed), `https://api.test${signed}`);
  assert.equal(
    normalizeAssetUrl("https://cdn.test/uploads/delivery/202608/proof.jpg?e=1&s=x"),
    "https://cdn.test/uploads/delivery/202608/proof.jpg?e=1&s=x"
  );
});
