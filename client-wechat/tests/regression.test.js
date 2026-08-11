const assert = require("node:assert/strict");
const test = require("node:test");

const {
  markerAnimationDuration,
  normalizeTracking,
  pollingIntervalMs,
  shouldContinueUnavailableTracking,
  trackingPollDelayMs
} = require("../pages/order-detail/tracking");
const { homeNavigationPlan } = require("../utils/navigation");
const { normalizeLotteryState } = require("../api/marketing");
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
