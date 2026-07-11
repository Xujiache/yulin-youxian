const { getProfile, updateProfile, uploadAvatar } = require("../../api/auth");
const { getCart } = require("../../api/cart");
const { getHome } = require("../../api/catalog");
const { requireCompleteProfile, requireLogin } = require("../../utils/auth-guard");
const { cachedAssetUrl, cacheImage } = require("../../utils/image-cache");
const { syncTheme } = require("../../utils/theme");

const DEFAULT_AVATAR_PATH = "/assets/products/avatar.png";
const PROFILE_HERO_BG_PATH = "/assets/products/profile-hero-bg.jpg";
const DEFAULT_AVATAR = cachedAssetUrl(DEFAULT_AVATAR_PATH);
const PROFILE_HERO_BG = cachedAssetUrl(PROFILE_HERO_BG_PATH);
const DEFAULT_CONTACT_PHONE = "400-800-1234";

const DEFAULT_ORDER_ACTIONS = [
  { label: "待付款", count: 0, url: "/pages/orders/index?status=待支付", icon: "/assets/icons/order-pending-payment.png" },
  { label: "待发货", count: 0, url: "/pages/orders/index?status=待接单", icon: "/assets/icons/order-pending-shipment.png" },
  { label: "待收货", count: 0, url: "/pages/orders/index?status=配送中", icon: "/assets/icons/order-pending-receipt.png" },
  { label: "待评价", count: 0, url: "/pages/orders/index?status=已完成", icon: "/assets/icons/order-pending-review.png" }
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
    glassMode: false,
    loading: true,
    heroBg: PROFILE_HERO_BG,
    avatarUrl: DEFAULT_AVATAR,
    displayName: "未登录用户",
    accountId: "",
    profileTip: "登录后可同步订单、地址和售后进度",
    isLoggedIn: false,
    editVisible: false,
    editAvatarUrl: DEFAULT_AVATAR,
    editAvatarTempPath: "",
    editNickName: "",
    savingProfile: false,
    avatarChoosing: false,
    cartCount: 0,
    storeName: "禹邻优鲜",
    contactPhone: DEFAULT_CONTACT_PHONE,
    orderActions: DEFAULT_ORDER_ACTIONS,
    menus: [
      { label: "地址管理", url: "/pages/address/index", icon: "/assets/icons/profile-menu-location.png" },
      { label: "售后退款", url: "/pages/orders/index", icon: "/assets/icons/profile-menu-refund.png" },
      { label: "联系客服", url: "", icon: "/assets/icons/profile-menu-service.png" },
      { label: "关于门店", url: "", icon: "/assets/icons/profile-menu-store.png" },
      { label: "设置", url: "/pages/settings/index", icon: "/assets/icons/profile-menu-settings.png" }
    ]
  },

  onShow() {
    syncTheme(this);
    this.refreshStaticAssets();
    this.loadStoreInfo();
    this.loadProfile();
    this.loadCartCount();
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

  refreshStaticAssets() {
    Promise.all([cacheImage(DEFAULT_AVATAR_PATH), cacheImage(PROFILE_HERO_BG_PATH)]).then(([avatarUrl, heroBg]) => {
      const shouldUseDefaultAvatar = !this.data.isLoggedIn && (!this.data.avatarUrl || this.data.avatarUrl === DEFAULT_AVATAR);
      this.setData({
        heroBg,
        avatarUrl: shouldUseDefaultAvatar ? avatarUrl : this.data.avatarUrl
      });
    });
  },

  async loadProfile() {
    const app = getApp();
    if (!app.isLoggedIn || !app.isLoggedIn()) {
      this.setData({
        loading: false,
        avatarUrl: DEFAULT_AVATAR,
        displayName: "未登录用户",
        accountId: "",
        profileTip: "登录后可同步订单、地址和售后进度",
        isLoggedIn: false,
        orderActions: buildOrderActions()
      });
      return;
    }
    const cachedProfile = wx.getStorageSync("userProfile") || {};
    if (cachedProfile.nickName || cachedProfile.avatarUrl) {
      this.setData({
        loading: false,
        avatarUrl: cachedProfile.avatarUrl || DEFAULT_AVATAR,
        displayName: cachedProfile.nickName || "微信用户",
        accountId: cachedProfile.userId || "",
        profileTip: "正在同步最新资料",
        isLoggedIn: true,
        orderActions: buildOrderActions(cachedProfile.orderStats)
      });
    } else {
      this.setData({
        loading: false,
        isLoggedIn: true,
        accountId: (app.globalData.user && app.globalData.user.userId) || ""
      });
    }
    try {
      const profile = await getProfile();
      this.setData({
        loading: false,
        avatarUrl: profile.avatarUrl || DEFAULT_AVATAR,
        displayName: profile.nickName || "微信用户",
        accountId: profile.userId || "",
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
        avatarUrl: DEFAULT_AVATAR,
        displayName: "未登录用户",
        accountId: "",
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
      editNickName: this.data.displayName,
      avatarChoosing: false
    });
  },

  closeEdit() {
    this.setData({ editVisible: false });
  },

  noop() {},

  handleChooseAvatar(event) {
    clearTimeout(this.avatarChooseTimer);
    this.setData({
      editAvatarUrl: event.detail.avatarUrl,
      editAvatarTempPath: event.detail.avatarUrl,
      avatarChoosing: false
    });
  },

  handleAvatarTap() {
    if (this.data.avatarChoosing || this.data.savingProfile) {
      return;
    }
    clearTimeout(this.avatarChooseTimer);
    this.setData({ avatarChoosing: true });
    this.avatarChooseTimer = setTimeout(() => {
      this.setData({ avatarChoosing: false });
    }, 5000);
  },

  onHide() {
    clearTimeout(this.avatarChooseTimer);
    this.setData({ avatarChoosing: false });
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
        accountId: profile.userId || this.data.accountId,
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
    if (label === "联系客服") {
      const phone = this.data.contactPhone || DEFAULT_CONTACT_PHONE;
      wx.showModal({
        title: "联系客服",
        content: `${this.data.storeName || "禹邻优鲜"}客服电话：${phone}`,
        confirmText: "拨打电话",
        cancelText: "取消",
        success(result) {
          if (!result.confirm) return;
          wx.makePhoneCall({
            phoneNumber: phone,
            fail() {
              wx.showToast({ title: "拨号失败，请稍后重试", icon: "none" });
            }
          });
        }
      });
      return;
    }
    wx.showModal({
      title: label || this.data.storeName || "禹邻优鲜",
      content: label === "关于门店"
        ? `${this.data.storeName || "禹邻优鲜"}，门店自配送。客服电话：${this.data.contactPhone || DEFAULT_CONTACT_PHONE}`
        : `客服电话：${this.data.contactPhone || DEFAULT_CONTACT_PHONE}`,
      showCancel: false
    });
  }
});
