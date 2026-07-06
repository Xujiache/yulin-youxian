import request from "./request";

export function getRefunds() {
  return request.get("/refunds");
}

export function approveRefund(id) {
  return request.post(`/refunds/${id}/approve`);
}

export function rejectRefund(id, reason) {
  return request.post(`/refunds/${id}/reject`, { reason });
}
