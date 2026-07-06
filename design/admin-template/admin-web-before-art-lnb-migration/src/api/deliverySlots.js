import request from "./request";

export function getDeliverySlots() {
  return request.get("/delivery-slots");
}

export function createDeliverySlot(data) {
  return request.post("/delivery-slots", data);
}

export function updateDeliverySlot(id, data) {
  return request.put(`/delivery-slots/${id}`, data);
}

export function deleteDeliverySlot(id) {
  return request.delete(`/delivery-slots/${id}`);
}

export function updateDeliverySlotStatus(id, available) {
  return request.put(`/delivery-slots/${id}/status`, { available });
}
