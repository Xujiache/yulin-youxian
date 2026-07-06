const { getProfile, updateProfile, uploadAvatar } = require("../../api/auth");
const { getCart } = require("../../api/cart");
const { requireCompleteProfile, requireLogin } = require("../../utils/auth-guard");

const DEFAULT_ORDER_ACTIONS = [
  { label: "待付款", count: 0, url: "/pages/orders/index?status=待支付", icon: "/assets/icons/order-veg.svg" },
  { label: "待发货", count: 0, url: "/pages/orders/index?status=待接单", icon: "/assets/icons/order-veg.svg" },
  { label: "待收货", count: 0, url: "/pages/orders/index?status=配送中", icon: "/assets/icons/order-veg.svg" },
  { label: "待评价", count: 0, url: "/pages/orders/index?status=已完成", icon: "/assets/icons/order-veg.svg" }
];

function buildOrderActions(stats) {
  const source = stats || [];
  return DEFAULT_ORDER_ACTIONS.map((item, index) => ({
    ...item,
    count: Number((source[index] && source[index].count) || 0)
  }));
}

Page({
  data: {
    loading: true,
    avatarUrl: "/assets/products/avatar.png",
    displayName: "未登录用户",
    profileTip: "登录后可同步订单、地址和售后进度",
    isLoggedIn: false,
    editVisible: false,
    editAvatarUrl: "/assets/products/avatar.png",
    editAvatarTempPath: "",
    editNickName: "",
    savingProfile: false,
    cartCount: 0,
    orderActions: DEFAULT_ORDER_ACTIONS,
    menus: [
      { label: "地址管理", url: "/pages/address/index", icon: "/assets/icons/location-green.svg" },
      { label: "售后退款", url: "/pages/orders/index", icon: "/assets/icons/refund-green.svg" },
      { label: "联系客服", url: "", icon: "/assets/icons/service-green.svg" },
      { label: "关于门店", url: "", icon: "/assets/icons/store-green.svg" },
      { label: "设置", url: "/pages/settings/index", icon: "/assets/icons/settings-green.svg" }
    ]
  },

  onShow() {
    this.loadProfile();
    this.loadCartCount();
  },

  async loadProfile() {
    const app = getApp();
    if (!app.isLoggedIn || !app.isLoggedIn()) {
      this.setData({
        loading: false,
        avatarUrl: "/assets/products/avatar.png",
        displayName: "未登录用户",
        profileTip: "登录后可同步订单、地址和售后进度",
        isLoggedIn: false,
        orderActions: buildOrderActions()
      });
      return;
    }
    try {
      const profile = await getProfile();
      this.setData({
        loading: false,
        avatarUrl: profile.avatarUrl || "/assets/products/avatar.png",
        displayName: profile.nickName || "微信用户",
        profileTip: "资料已同步，换设备登录也会保持一致",
        isLoggedIn: true,
        orderActions: buildOrderActions(profile.orderStats)
      });
    } catch {
      if (app.clearLogin) {
        app.clearLogin();
      }
      this.setData({
        loading: false,
        avatarUrl: "/assets/products/avatar.png",
        displayName: "未登录用户",
        profileTip: "登录后可同步订单、地址和售后进度",
        isLoggedIn: false,
        orderActions: buildOrderActions()
      });
    }
  },

  async loadCartCount() {
    const app = getApp();
    if (!app.isLoggedIn || !app.isLoggedIn()) {
      this.setData({ cartCount: 0 });
      return;
    }
    try {
      const cart = await getCart();
      this.setData({ cartCount: (cart.items || []).length });
    } catch {}
  },

  handleProfileAction() {
    const app = getApp();
    if (!app.isLoggedIn || !app.isLoggedIn()) {
      wx.navigateTo({ url: "/pages/login/index?redirect=%2Fpages%2Fprofile%2Findex" });
      return;
    }
    this.setData({
      editVisible: true,
      editAvatarUrl: this.data.avatarUrl,
      editAvatarTempPath: "",
      editNickName: this.data.displayName
    });
  },

  closeEdit() {
    this.setData({ editVisible: false });
  },

  noop() {},

  handleChooseAvatar(event) {
    this.setData({
      editAvatarUrl: event.detail.avatarUrl,
      editAvatarTempPath: event.detail.avatarUrl
    });
  },

  handleNameInput(event) {
    this.setData({ editNickName: event.detail.value });
  },

  async saveProfile() {
    const nickName = String(this.data.editNickName || "").trim();
    if (!nickName) {
      wx.showToast({ title: "请输入昵称", icon: "none" });
      return;
    }
    if (this.data.savingProfile) {
      return;
    }
    this.setData({ savingProfile: true });
    try {
      let avatarUrl = "";
      let displayAvatar = this.data.editAvatarUrl;
      if (this.data.editAvatarTempPath && !this.data.editAvatarTempPath.startsWith("http") && !this.data.editAvatarTempPath.startsWith("/assets")) {
        const uploaded = await uploadAvatar(this.data.editAvatarTempPath);
        avatarUrl = uploaded.avatarUrl;
        displayAvatar = uploaded.displayUrl;
      }
      const profile = await updateProfile({ nickName, avatarUrl });
      const app = getApp();
      app.globalData.user = {
        ...(app.globalData.user || {}),
        ...profile,
        profileCompleted: true,
        avatarUrl: displayAvatar || profile.avatarUrl
      };
      wx.setStorageSync("userProfile", app.globalData.user);
      this.setData({
        avatarUrl: displayAvatar || profile.avatarUrl,
        displayName: profile.nickName,
        profileTip: "资料已同步，换设备登录也会保持一致",
        editVisible: false,
        isLoggedIn: true
      });
      wx.showToast({ title: "资料已保存", icon: "success" });
    } catch (error) {
      wx.showToast({ title: error.message || "保存失败", icon: "none" });
    } finally {
      this.setData({ savingProfile: false });
    }
  },

  goOrders() {
    if (!requireLogin("/pages/profile/index")) {
      return;
    }
    wx.navigateTo({ url: "/pages/orders/index" });
  },

  handleOrder(event) {
    if (!requireLogin("/pages/profile/index")) {
      return;
    }
    wx.navigateTo({ url: event.currentTarget.dataset.url });
  },

  handleMenu(event) {
    const url = event.currentTarget.dataset.url;
    const label = event.currentTarget.dataset.label;
    if (url) {
      if (url !== "/pages/settings/index" && !requireCompleteProfile("/pages/profile/index")) {
        return;
      }
      wx.navigateTo({ url });
      return;
    }
    wx.showModal({
      title: label || "禹邻优鲜",
      content: label === "关于门店" ? "禹邻优鲜，门店自配送。" : "客服电话：400-800-1234",
      showCancel: false
    });
  }
});
