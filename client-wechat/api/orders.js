const request = require("../utils/request");
const { normalizeOrder, normalizeOrderItem } = require("./normalize");
const { API_BASE_URL } = require("../utils/config");

function getBaseUrl() {
  const app = getApp();
  return app.globalData.apiBaseUrl || API_BASE_URL;
}

function normalizeUploadUrl(url) {
  if (!url || url.startsWith("http") || url.startsWith("/assets")) {
    return url;
  }
  return `${getBaseUrl()}${url}`;
}

async function getOrders(params = {}) {
  const orders = await request({
    url: "/api/wx/orders",
    data: params
  });
  return orders.map(normalizeOrder);
}

async function getOrder(id) {
  const order = await request({
    url: `/api/wx/orders/${id}`
  });
  return {
    ...order,
    items: (order.items || []).map(normalizeOrderItem)
  };
}

async function previewOrder(data) {
  const preview = await request({
    url: "/api/wx/orders/preview",
    method: "POST",
    data
  });
  return {
    ...preview,
    items: (preview.items || []).map(normalizeOrderItem)
  };
}

function createOrder(data) {
  return request({
    url: "/api/wx/orders",
    method: "POST",
    data
  });
}

function payOrder(id) {
  return request({
    url: `/api/wx/orders/${id}/pay`,
    method: "POST"
  });
}

function confirmDevelopmentPayment(id) {
  return request({
    url: `/api/wx/orders/${id}/pay/development-success`,
    method: "POST"
  });
}

function submitRefund(data) {
  return request({
    url: "/api/wx/refunds",
    method: "POST",
    data
  });
}

function uploadRefundEvidence(filePath) {
  const app = getApp();
  const token = app.globalData.authToken || wx.getStorageSync("authToken");
  const baseUrl = getBaseUrl();
  return new Promise((resolve, reject) => {
    if (!baseUrl) {
      reject(new Error("请先配置后端服务地址"));
      return;
    }
    wx.uploadFile({
      url: `${baseUrl}/api/wx/refunds/evidence`,
      filePath,
      name: "file",
      header: {
        Authorization: `Bearer ${token}`
      },
      success(response) {
        let body = {};
        try {
          body = JSON.parse(response.data || "{}");
        } catch {
          reject(new Error("凭证上传响应解析失败"));
          return;
        }
        if (response.statusCode >= 200 && response.statusCode < 300 && body.code === 0) {
          const url = body.data && body.data.url;
          resolve({
            url,
            displayUrl: normalizeUploadUrl(url)
          });
          return;
        }
        reject(new Error(body.message || "凭证上传失败"));
      },
      fail() {
        reject(new Error("凭证上传失败，请检查网络"));
      }
    });
  });
}

module.exports = {
  getOrders,
  getOrder,
  previewOrder,
  createOrder,
  payOrder,
  confirmDevelopmentPayment,
  submitRefund,
  uploadRefundEvidence
};
