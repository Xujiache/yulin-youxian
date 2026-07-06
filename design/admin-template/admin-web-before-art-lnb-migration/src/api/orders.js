import request from "./request";

export function getOrders(params = {}) {
  return request.get("/orders", { params });
}

export function acceptOrder(id) {
  return request.post(`/orders/${id}/accept`);
}

export function prepareOrder(id) {
  return request.post(`/orders/${id}/prepare`);
}

export function deliverOrder(id) {
  return request.post(`/orders/${id}/deliver`);
}

export function completeOrder(id) {
  return request.post(`/orders/${id}/complete`);
}

export function cancelOrder(id) {
  return request.post(`/orders/${id}/cancel`);
}
