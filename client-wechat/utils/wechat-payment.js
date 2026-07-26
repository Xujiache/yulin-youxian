const { getOrder } = require("../api/orders");

const PAID_STATUS = "\u5df2\u652f\u4ed8";
const PENDING_PAYMENT_STATUS = "\u5f85\u652f\u4ed8";

function wait(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

function isPaidOrder(order) {
  return Boolean(order && typeof order.status === "string" && order.status.includes(PAID_STATUS));
}

function isPendingPaymentOrder(order) {
  return Boolean(order && order.status === PENDING_PAYMENT_STATUS);
}

function isPaymentCancelled(error) {
  const message = String((error && (error.errMsg || error.message)) || "").toLowerCase();
  return message.includes("cancel") || message.includes("\u53d6\u6d88");
}

function requestWechatPayment(payment) {
  if (!payment || payment.developmentMode) {
    return Promise.resolve({ developmentMode: true });
  }
  return new Promise((resolve, reject) => {
    wx.requestPayment({
      timeStamp: payment.timeStamp,
      nonceStr: payment.nonceStr,
      package: payment.packageValue,
      signType: payment.signType,
      paySign: payment.paySign,
      success: resolve,
      fail: reject
    });
  });
}

async function waitForPaymentResult(orderId, options = {}) {
  const attempts = Number(options.attempts || 8);
  const interval = Number(options.interval || 1000);
  let latestOrder = null;
  let lastError = null;

  for (let attempt = 0; attempt < attempts; attempt += 1) {
    try {
      latestOrder = await getOrder(orderId);
      lastError = null;
      if (isPaidOrder(latestOrder) || !isPendingPaymentOrder(latestOrder)) {
        return latestOrder;
      }
    } catch (error) {
      lastError = error;
    }
    if (attempt < attempts - 1) {
      await wait(interval);
    }
  }

  if (latestOrder) {
    return latestOrder;
  }
  throw lastError || new Error("\u6682\u65e0\u6cd5\u786e\u8ba4\u652f\u4ed8\u7ed3\u679c");
}

module.exports = {
  isPaidOrder,
  isPendingPaymentOrder,
  isPaymentCancelled,
  requestWechatPayment,
  waitForPaymentResult
};
