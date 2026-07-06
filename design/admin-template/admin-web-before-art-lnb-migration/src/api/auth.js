import request from "./request";

export function login(data) {
  return request.post("/auth/login", data);
}

export function getProfile() {
  return request.get("/auth/profile");
}

export function logout() {
  return request.post("/auth/logout");
}
