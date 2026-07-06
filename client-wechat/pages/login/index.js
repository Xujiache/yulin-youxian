const { assetUrl } = require("../../utils/config");

Page({
  data: {
    loading: true,
    redirect: "",
    submitting: false,
    brandImage: assetUrl("/assets/products/login-hero-illustration.jpg")
  },

  onLoad(options) {
    this.setData({
      redirect: decodeURIComponent(options.redirect || ""),
      loading: false
    });
  },

  goBack() {
    wx.navigateBack({
      delta: 1,
      fail() {
        wx.redirectTo({ url: "/pages/home/index" });
      }
    });
  },

  async handleLogin() {
    if (this.data.submitting) {
      return;
    }
    this.setData({ submitting: true });
    try {
      const app = getApp();
      const user = await app.loginWithProfile();
      wx.showToast({ title: "登录成功", icon: "success" });
      setTimeout(() => {
        if (!user.profileCompleted) {
          wx.redirectTo({
            url: `/pages/profile-setup/index?redirect=${encodeURIComponent(this.data.redirect || "/pages/profile/index")}`
          });
          return;
        }
        this.redirectBack();
      }, 350);
    } catch (error) {
      wx.showToast({ title: error.message || "登录失败", icon: "none" });
    } finally {
      this.setData({ submitting: false });
    }
  },

  redirectBack() {
    if (this.data.redirect && this.data.redirect !== "/pages/login/index") {
      wx.redirectTo({ url: this.data.redirect });
      return;
    }
    const pages = getCurrentPages();
    if (pages.length > 1) {
      wx.navigateBack({ delta: 1 });
      return;
    }
    wx.redirectTo({ url: "/pages/home/index" });
  }
});
