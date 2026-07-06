import axios from "axios";

const request = axios.create({
  baseURL: "/api/admin",
  timeout: 10000
});

request.interceptors.request.use((config) => {
  const token = localStorage.getItem("adminToken");
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

request.interceptors.response.use((response) => {
  const body = response.data;
  if (body && body.code === 0) {
    return body.data;
  }
  return Promise.reject(new Error(body?.message || "请求失败"));
}, (error) => {
  if (error.response?.status === 401 || error.response?.data?.code === 401) {
    localStorage.removeItem("adminToken");
    if (window.location.pathname !== "/login") {
      window.location.href = "/login";
    }
  }
  return Promise.reject(new Error(error.response?.data?.message || error.message || "请求失败"));
});

export default request;
