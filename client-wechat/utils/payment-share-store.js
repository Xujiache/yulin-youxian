// 后端不下发订单当前的代付 token，只能在本机记住最近一次生成的链接。
// 重新调用 createPaymentShare 会作废好友手上的旧链接，所以付款前先复用可用的旧链接。

const STORAGE_KEY = "orderPaymentShareTokens";
const MAX_TOKEN_AGE = 6 * 60 * 60 * 1000;
const MAX_TOKEN_COUNT = 20;

function readAll() {
  try {
    const stored = wx.getStorageSync(STORAGE_KEY);
    return stored && typeof stored === "object" ? stored : {};
  } catch {
    return {};
  }
}

function writeAll(map) {
  try {
    wx.setStorageSync(STORAGE_KEY, map);
  } catch {}
}

function prune(map, now = Date.now()) {
  const entries = Object.keys(map)
    .map((key) => [key, map[key]])
    .filter(([, value]) => value && value.token && now - Number(value.createdAt || 0) <= MAX_TOKEN_AGE)
    .sort((left, right) => Number(right[1].createdAt || 0) - Number(left[1].createdAt || 0))
    .slice(0, MAX_TOKEN_COUNT);
  const pruned = {};
  entries.forEach(([key, value]) => {
    pruned[key] = value;
  });
  return pruned;
}

function rememberPaymentShareToken(orderId, token) {
  const key = String(Number(orderId) || 0);
  if (key === "0" || !token) {
    return;
  }
  const map = prune(readAll());
  map[key] = { token: String(token), createdAt: Date.now() };
  writeAll(map);
}

function readPaymentShareToken(orderId) {
  const key = String(Number(orderId) || 0);
  if (key === "0") {
    return "";
  }
  const map = prune(readAll());
  const entry = map[key];
  return entry && entry.token ? String(entry.token) : "";
}

function forgetPaymentShareToken(orderId) {
  const key = String(Number(orderId) || 0);
  const map = prune(readAll());
  if (!map[key]) {
    writeAll(map);
    return;
  }
  delete map[key];
  writeAll(map);
}

// REUSABLE：链接仍然有效且未付款，直接跳回原链接
// PAID：好友已经付过，交给订单状态刷新处理
// EXPIRED：token 已失效，可以安全地重新生成
// UNKNOWN：网络异常，不能默认作废旧链接
function paymentShareReuseState({ share, error } = {}) {
  if (share && typeof share === "object") {
    const status = String(share.paymentStatus || share.status || "").toUpperCase();
    if (
      share.paid === true
      || share.paymentFinished === true
      || ["PAID", "SUCCESS", "COMPLETED"].includes(status)
    ) {
      return "PAID";
    }
    return "REUSABLE";
  }
  // 业务码在 error.code 上（HTTP 层永远是 200），statusCode 只作为网关错误的兜底。
  const code = Number(error && (error.code || error.statusCode));
  if ([404, 410].includes(code)) {
    return "EXPIRED";
  }
  return "UNKNOWN";
}

async function inspectPaymentShare(orderId, fetchShare) {
  const token = readPaymentShareToken(orderId);
  if (!token || typeof fetchShare !== "function") {
    return { token: "", state: "NONE" };
  }
  let state = "UNKNOWN";
  try {
    state = paymentShareReuseState({ share: await fetchShare(token) });
  } catch (error) {
    state = paymentShareReuseState({ error });
  }
  if (state === "EXPIRED") {
    forgetPaymentShareToken(orderId);
    return { token: "", state };
  }
  return { token, state };
}

module.exports = {
  forgetPaymentShareToken,
  inspectPaymentShare,
  paymentShareReuseState,
  readPaymentShareToken,
  rememberPaymentShareToken
};
