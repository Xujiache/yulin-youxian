const { cachedAssetUrl, cacheImage } = require("../../utils/image-cache");
const { syncTheme } = require("../../utils/theme");

const LOGIN_HERO = "/assets/products/login-hero-illustration.jpg";

Page({
  data: {
    glassMode: false,
    loading: true,
    redirect: "",
    submitting: false,
    agreed: false,
    brandImage: cachedAssetUrl(LOGIN_HERO)
  },

  onLoad(options) {
    syncTheme(this);
    this.setData({
      redirect: decodeURIComponent(options.redirect || "")
    });
    cacheImage(LOGIN_HERO).then((brandImage) => {
      this.setData({
        brandImage,
        loading: false
      });
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

  toggleAgreement() {
    this.setData({ agreed: !this.data.agreed });
  },

  showAgreement(event) {
    const type = event.currentTarget.dataset.type;
    const isPrivacy = type === "privacy";
    wx.showModal({
      title: isPrivacy ? "隐私政策" : "用户协议",
      content: isPrivacy
        ? "我们仅在账号识别、下单配送、支付退款、售后联系所需范围内使用必要信息，包括头像昵称、收货地址、联系方式、订单记录和支付状态。信息仅用于禹邻优鲜门店履约服务，不会用于无关用途。"
        : "用户使用禹邻优鲜下单后，由门店按预约配送时间自行配送。称重商品以页面选择重量下单，实际履约、退款、售后和支付结果以订单状态及微信支付记录为准。",
      showCancel: false,
      confirmText: "知道了"
    });
  },

  async handleLogin() {
    if (!this.data.agreed) {
      wx.showToast({ title: "请先阅读并同意协议", icon: "none" });
      return;
    }
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
