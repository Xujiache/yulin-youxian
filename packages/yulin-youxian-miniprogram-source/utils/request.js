const { API_BASE_URL } = require("./config");

const DEFAULT_BASE_URL = API_BASE_URL;
const DEFAULT_TIMEOUT = 8000;

function normalizeError(error) {
  const message = (error && error.errMsg) || "";
  if (message.includes("timeout")) {
    return new Error("请求超时，请确认后端服务已启动");
  }
  if (message.includes("fail")) {
    return new Error("网络请求失败，请检查后端服务");
  }
  return error instanceof Error ? error : new Error("请求失败");
}

function request(options) {
  const app = getApp();
  const baseUrl = app.globalData.apiBaseUrl || DEFAULT_BASE_URL;
  if (!baseUrl) {
    return Promise.reject(new Error("请先配置后端服务地址"));
  }

  const send = () => new Promise((resolve, reject) => {
    const token = app.globalData.authToken || wx.getStorageSync("authToken");
    const header = {
      "content-type": "application/json",
      ...(options.header || {})
    };
    if (token) {
      header.Authorization = `Bearer ${token}`;
    }
    wx.request({
      url: `${baseUrl}${options.url}`,
      method: options.method || "GET",
      data: options.data || {},
      header,
      timeout: options.timeout || DEFAULT_TIMEOUT,
      success(response) {
        const body = response.data;
        if (response.statusCode >= 200 && response.statusCode < 300 && body && body.code === 0) {
          resolve(body.data);
          return;
        }
        if (response.statusCode === 401 || (body && body.code === 401)) {
          const error = new Error((body && body.message) || "请先登录");
          error.code = 401;
          reject(error);
          return;
        }
        reject(new Error((body && body.message) || "请求失败"));
      },
      fail(error) {
        reject(normalizeError(error));
      }
    });
  });

  if (options.skipAuth || !app.ensureLogin) {
    return send();
  }
  return app.ensureLogin().then(send).catch((error) => {
    if (error && error.loginRequired) {
      throw error;
    }
    if (error && error.code === 401 && !options._retried && app.clearLogin) {
      app.clearLogin();
      return request({
        ...options,
        _retried: true
      });
    }
    throw error;
  });
}

module.exports = request;
