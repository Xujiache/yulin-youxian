const { getPaymentShare, refreshPaymentStatus } = require("../api/orders");

const PAID_STATUS = "\u5df2\u652f\u4ed8";
const PENDING_PAYMENT_STATUS = "\u5f85\u652f\u4ed8";

function wait(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

function isPaidOrder(order) {
  if (!order) return false;
  if (Number(order.paidAmount || 0) > 0) return true;
  const status = String(order.status || "");
  return status.includes(PAID_STATUS)
    || ["待接单", "备货中", "配送中", "已完成", "退款中", "部分退款", "已退款"].includes(status);
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

function paymentShareConfirmationState({ share, error } = {}) {
  if (share && typeof share === "object") {
    const status = String(share.paymentStatus || share.status || "").toUpperCase();
    if (
      share.paid === true
      || share.paymentFinished === true
      || ["PAID", "SUCCESS", "COMPLETED"].includes(status)
    ) {
      return "CONFIRMED";
    }
    return "PENDING";
  }
  const code = Number(error && (error.statusCode || error.code));
  if ([404, 410].includes(code)) {
    // 代付成功后服务端会让一次性 token 失效；支付前已成功读取过该 token，因此失效可作为确认信号。
    return "CONFIRMED";
  }
  return "UNKNOWN";
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

async function waitForPaymentShareResult(token, options = {}) {
  const attempts = Math.max(1, Number(options.attempts || 8));
  const interval = Math.max(0, Number(options.interval || 1000));
  const fetchShare = typeof options.fetchShare === "function" ? options.fetchShare : getPaymentShare;
  let state = "UNKNOWN";
  let lastError = null;

  for (let attempt = 0; attempt < attempts; attempt += 1) {
    try {
      const share = await fetchShare(token);
      state = paymentShareConfirmationState({ share });
      lastError = null;
    } catch (error) {
      state = paymentShareConfirmationState({ error });
      lastError = error;
    }
    if (state === "CONFIRMED") {
      return { confirmed: true, state, error: null };
    }
    if (attempt < attempts - 1) {
      await wait(interval);
    }
  }

  return { confirmed: false, state, error: lastError };
}

module.exports = {
  isPaidOrder,
  isPendingPaymentOrder,
  isPaymentCancelled,
  paymentErrorMessage,
  paymentShareConfirmationState,
  requestWechatPayment,
  waitForPaymentResult,
  waitForPaymentShareResult
};
