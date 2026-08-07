const { yuan } = require("./format");

function normalizeMinOrderAmount(value) {
  const amount = Number(value || 0);
  return Number.isFinite(amount) && amount > 0 ? Math.round(amount) : 0;
}

function buildMinOrderState(productAmountFen, minOrderAmountFen) {
  const minOrderAmount = normalizeMinOrderAmount(minOrderAmountFen);
  const productAmount = Math.max(0, Math.round(Number(productAmountFen || 0)));
  const shortfall = minOrderAmount > 0 ? Math.max(0, minOrderAmount - productAmount) : 0;
  const met = shortfall <= 0;
  const minOrderText = minOrderAmount > 0 ? yuan(minOrderAmount) : "";
  const shortfallText = shortfall > 0 ? yuan(shortfall) : "";
  let tipText = "";
  let checkoutLabel = "去结算";
  if (minOrderAmount > 0) {
    tipText = met ? `起送¥${minOrderText}` : `起送¥${minOrderText} · 还差¥${shortfallText}`;
    checkoutLabel = met ? "去结算" : `还差¥${shortfallText}起送`;
  }
  return {
    minOrderAmount,
    minOrderText,
    shortfall,
    shortfallText,
    minOrderMet: met,
    minOrderTip: tipText,
    checkoutLabel,
    payLabel: met || minOrderAmount <= 0 ? "微信支付" : `还差¥${shortfallText}起送`
  };
}

module.exports = {
  normalizeMinOrderAmount,
  buildMinOrderState
};
