const { API_BASE_URL } = require("./utils/config");
const { preloadStaticImages } = require("./utils/image-cache");

function currentRoute() {
  const pages = getCurrentPages();
  const current = pages[pages.length - 1];
  if (!current) {
    return "";
  }
  const query = current.options || {};
  const queryString = Object.keys(query)
    .map((key) => `${key}=${encodeURIComponent(query[key])}`)
    .join("&");
  return `/${current.route}${queryString ? `?${queryString}` : ""}`;
}

App({
  globalData: {
    apiBaseUrl: API_BASE_URL,
    cartCount: 0,
    authToken: "",
    user: null,
    glassMode: false
  },

  onLaunch() {
    this.globalData.glassMode = Boolean(wx.getStorageSync("glassMode"));
    preloadStaticImages().catch(() => []);
    const cachedToken = wx.getStorageSync("authToken");
    const cachedUser = wx.getStorageSync("userProfile");
    const loginConfirmed = wx.getStorageSync("loginConfirmed");
    if (cachedToken && loginConfirmed) {
      this.globalData.authToken = cachedToken;
      this.globalData.user = cachedUser || null;
      return;
    }
    if (cachedToken && !loginConfirmed) {
      this.clearLogin();
    }
  },

  ensureLogin() {
    if (this.isLoggedIn()) {
      return Promise.resolve(this.globalData.user || wx.getStorageSync("userProfile"));
    }
    this.clearLogin();
    this.goLogin();
    const error = new Error("请先登录");
    error.code = 401;
    error.loginRequired = true;
    return Promise.reject(error);
  },

  loginWithProfile(profile = {}) {
    if (this.loginPromise) {
      return this.loginPromise;
    }
    if (!this.globalData.apiBaseUrl) {
      return Promise.reject(new Error("请先配置后端服务地址"));
    }
    this.loginPromise = new Promise((resolve, reject) => {
      wx.login({
        success: ({ code }) => {
          if (!code) {
            reject(new Error("微信登录失败，请重试"));
            return;
          }
          wx.request({
            url: `${this.globalData.apiBaseUrl}/api/wx/auth/login`,
            method: "POST",
            timeout: 15000,
            data: {
              code,
              nickName: profile.nickName || "",
              avatarUrl: profile.avatarUrl || ""
            },
            header: {
              "content-type": "application/json"
            },
            success: (response) => {
              const body = response.data || {};
              if (response.statusCode >= 200 && response.statusCode < 300 && body.code === 0) {
                const data = body.data || {};
                this.setLoginState(data);
                resolve(data);
                return;
              }
              reject(new Error((body && body.message) || "登录失败"));
            },
            fail: () => reject(new Error("登录请求失败，请确认后端服务已启动"))
          });
        },
        fail: () => reject(new Error("微信登录失败，请重试"))
      });
    }).finally(() => {
      this.loginPromise = null;
    });
    return this.loginPromise;
  },

  setLoginState(user) {
    this.globalData.authToken = user.token || this.globalData.authToken || wx.getStorageSync("authToken") || "";
    this.globalData.user = user || null;
    if (user.token) {
      wx.setStorageSync("authToken", user.token);
    }
    wx.setStorageSync("userProfile", user || {});
    wx.setStorageSync("loginConfirmed", true);
  },

  isLoggedIn() {
    return Boolean((this.globalData.authToken || wx.getStorageSync("authToken")) && wx.getStorageSync("loginConfirmed"));
  },

  goLogin() {
    const pages = getCurrentPages();
    const current = pages[pages.length - 1];
    if (current && current.route === "pages/login/index") {
      return;
    }
    wx.navigateTo({
      url: `/pages/login/index?redirect=${encodeURIComponent(currentRoute())}`
    });
  },

  clearLogin() {
    this.globalData.authToken = "";
    this.globalData.user = null;
    wx.removeStorageSync("authToken");
    wx.removeStorageSync("userProfile");
    wx.removeStorageSync("loginConfirmed");
  }
});
