// 结算页会先建单再抽奖，待支付订单在页面上可以被复用。
// 一旦地址、配送时段、备注或商品来源变化，旧订单就必须作废，否则会出现「显示新信息、付款旧订单」。

function sourceKey({ buyNowRequest, cartItemIds }) {
  if (buyNowRequest) {
    return [
      "buy",
      Number(buyNowRequest.productId) || 0,
      Number(buyNowRequest.skuId) || 0,
      Number(buyNowRequest.quantity) || 0
    ].join(":");
  }
  const ids = (Array.isArray(cartItemIds) ? cartItemIds : [])
    .map(Number)
    .filter((id) => Number.isFinite(id) && id > 0)
    .sort((left, right) => left - right);
  return `cart:${ids.join(",")}`;
}

function pendingOrderSignature(input = {}) {
  return [
    Number(input.addressId) || 0,
    Number(input.deliverySlotId) || 0,
    String(input.remark || "").trim(),
    sourceKey(input)
  ].join("|");
}

// DRAWN：已经抽到结果并锁定了金额；SHARED：已经用一条朋友圈换到了抽奖资格
function pendingOrderLockKind(lotteryState) {
  if (!lotteryState) {
    return "";
  }
  if (lotteryState.drawn) {
    return "DRAWN";
  }
  if (lotteryState.shareTriggered) {
    return "SHARED";
  }
  return "";
}

// create：还没有待支付订单；reuse：信息没变可以直接付
// recreate：信息变了且没有鲜礼可丢，静默重新建单
// confirm：信息变了但会丢掉已锁定的鲜礼，必须先问用户
function pendingOrderDecision(input = {}) {
  if (!Number(input.pendingOrderId)) {
    return { action: "create", lockKind: "" };
  }
  if (input.signature && input.signature === input.pendingSignature) {
    return { action: "reuse", lockKind: "" };
  }
  const lockKind = pendingOrderLockKind(input.lotteryState);
  return { action: lockKind ? "confirm" : "recreate", lockKind };
}

module.exports = {
  pendingOrderDecision,
  pendingOrderLockKind,
  pendingOrderSignature
};
