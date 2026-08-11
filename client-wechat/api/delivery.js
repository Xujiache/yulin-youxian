const request = require("../utils/request");

function getDeliverySlots() {
  return request({
    url: "/api/wx/delivery-slots"
  });
}

// ==================== 配送可视化（docs/rider/04-API契约.md §三） ====================

// 顾客侧配送追踪；页面按响应 polling.intervalSeconds 调度，终态后停止。
function getTracking(orderId) {
  return request({
    url: `/api/wx/delivery/orders/${orderId}/tracking`
  });
}

// 记录一次性订阅消息授权（已接单/已出发/即将送达）
function subscribeDelivery(orderId, templateIds) {
  return request({
    url: `/api/wx/delivery/orders/${orderId}/subscribe`,
    method: "POST",
    data: { templateIds: templateIds || [] }
  });
}

// data: { star, tags, comment }
function submitRating(orderId, data) {
  return request({
    url: `/api/wx/delivery/orders/${orderId}/rating`,
    method: "POST",
    data
  });
}

module.exports = {
  getDeliverySlots,
  getTracking,
  subscribeDelivery,
  submitRating
};
