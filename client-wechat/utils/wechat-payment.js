const { refreshPaymentStatus } = require("../api/orders");

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

function paymentErrorMessage(error, fallback = "支付失败，请稍后重试") {
  const rawMessage = String((error && (error.errMsg || error.message)) || "").trim();
  if (!rawMessage) {
    return fallback;
  }
  return rawMessage
    .replace(/^requestPayment:fail\s*/i, "")
    .replace(/^requestPayment\s*:\s*/i, "")
    .trim() || fallback;
}

function requestWechatPayment(payment) {
  const requiredFields = ["timeStamp", "nonceStr", "packageValue", "signType", "paySign"];
  if (!payment || requiredFields.some((field) => !payment[field])) {
    return Promise.reject(new Error("支付参数不完整，请稍后重试"));
  }
  const timeStamp = String(payment.timeStamp || "");
  const nonceStr = String(payment.nonceStr || "");
  const packageValue = String(payment.packageValue || payment.package || "");
  const signType = String(payment.signType || "RSA");
  const paySign = String(payment.paySign || "");
  if (!timeStamp || !nonceStr || !packageValue || !paySign) {
    return Promise.reject(new Error("微信支付参数不完整，请刷新订单后重试"));
  }
  return new Promise((resolve, reject) => {
    wx.requestPayment({
      timeStamp,
      nonceStr,
      package: packageValue,
      signType,
      paySign,
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
      latestOrder = await refreshPaymentStatus(orderId);
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
  paymentErrorMessage,
  requestWechatPayment,
  waitForPaymentResult
};
