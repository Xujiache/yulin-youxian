const { getHome } = require("../../api/catalog");
const { setGlassMode, syncTheme } = require("../../utils/theme");

const DEFAULT_CONTACT_PHONE = "400-800-1234";

Page({
  data: {
    glassMode: false,
    loading: true,
    isLoggedIn: false,
    storeName: "禹邻优鲜",
    contactPhone: DEFAULT_CONTACT_PHONE,
    items: [
      { key: "glass", label: "液态玻璃", desc: "开启高质感半透明材质，低端设备自动降级" },
      { key: "profile", label: "编辑个人资料", desc: "修改头像和昵称，并同步到云端" },
      { key: "privacy", label: "隐私政策", desc: "查看个人信息收集与使用说明" },
      { key: "agreement", label: "用户协议", desc: "查看下单、配送和售后规则" },
      { key: "cache", label: "清除缓存", desc: "清理本地搜索记录和临时数据" },
      { key: "store", label: "关于门店", desc: "查看门店配送与联系方式" }
    ]
  },

  onShow() {
    syncTheme(this);
    const app = getApp();
    this.setData({
      isLoggedIn: Boolean(app.isLoggedIn && app.isLoggedIn()),
      loading: false
    });
    this.loadStoreInfo();
  },

  async loadStoreInfo() {
    try {
      const home = await getHome();
      this.setData({
        storeName: home.storeName || "禹邻优鲜",
        contactPhone: home.contactPhone || DEFAULT_CONTACT_PHONE
      });
    } catch {}
  },

  goBack() {
    wx.navigateBack({ delta: 1 });
  },

  handleItem(event) {
    const key = event.currentTarget.dataset.key;
    if (key === "glass") {
      return;
    }
    if (key === "profile") {
      if (!this.data.isLoggedIn) {
        wx.navigateTo({ url: "/pages/login/index?redirect=%2Fpages%2Fsettings%2Findex" });
        return;
      }
      wx.navigateTo({ url: "/pages/profile-setup/index?mode=edit&redirect=%2Fpages%2Fsettings%2Findex" });
      return;
    }
    if (key === "privacy") {
      this.showText("隐私政策", "我们仅在下单、配送、售后和账号识别所需范围内使用必要信息，包括收货地址、联系方式、订单记录和支付状态。信息仅用于本门店履约服务，不会用于无关用途。");
      return;
    }
    if (key === "agreement") {
      this.showText("用户协议", "用户提交订单后，由门店按预约时间自行配送。称重商品以页面选择重量下单，退款、售后和支付结果以实际订单状态为准。");
      return;
    }
    if (key === "cache") {
      wx.removeStorageSync("recentSearchKeywords");
      wx.showToast({ title: "缓存已清理", icon: "success" });
      return;
    }
    if (key === "store") {
      this.showText(
        "关于门店",
        `${this.data.storeName || "禹邻优鲜"}支持门店自配送和预约配送。客服电话：${this.data.contactPhone || DEFAULT_CONTACT_PHONE}。`
      );
    }
  },

  handleGlassChange(event) {
    const glassMode = setGlassMode(event.detail.value);
    this.setData({ glassMode });
    wx.showToast({ title: glassMode ? "液态玻璃已开启" : "已恢复标准模式", icon: "none" });
  },

  noop() {},

  showText(title, content) {
    wx.showModal({
      title,
      content,
      showCancel: false,
      confirmText: "知道了"
    });
  },

  handleLogout() {
    if (!this.data.isLoggedIn) {
      wx.showToast({ title: "当前未登录", icon: "none" });
      return;
    }
    wx.showModal({
      title: "退出登录",
      content: "退出后，本机将清除登录状态；再次查看订单或结算时需要重新登录。",
      confirmText: "退出",
      confirmColor: "#d93030",
      success: (result) => {
        if (!result.confirm) {
          return;
        }
        const app = getApp();
        if (app.clearLogin) {
          app.clearLogin();
        }
        this.setData({ isLoggedIn: false });
        wx.showToast({ title: "已退出登录", icon: "success" });
        setTimeout(() => {
          wx.navigateBack({ delta: 1 });
        }, 500);
      }
    });
  }
});
