const { getProfile, updateProfile, uploadAvatar } = require("../../api/auth");
const { cachedAssetUrl, cacheImage } = require("../../utils/image-cache");

const DEFAULT_AVATAR_PATH = "/assets/products/avatar.png";
const SETUP_BG_PATH = "/assets/products/setup-hero-bg.jpg";
const DEFAULT_AVATAR = cachedAssetUrl(DEFAULT_AVATAR_PATH);
const SETUP_BG = cachedAssetUrl(SETUP_BG_PATH);

Page({
  data: {
    loading: true,
    redirect: "",
    setupBg: SETUP_BG,
    avatarUrl: DEFAULT_AVATAR,
    avatarTempPath: "",
    nickName: "",
    mode: "setup",
    pageTitle: "设置个人信息",
    pageSubtitle: "首次登录需要设置头像和昵称，后续会同步到云端。",
    fieldTip: "首次设置必填",
    submitText: "完成设置",
    submitting: false
  },

  onLoad(options) {
    const isEdit = options.mode === "edit";
    this.setData({
      redirect: decodeURIComponent(options.redirect || ""),
      mode: isEdit ? "edit" : "setup",
      pageTitle: isEdit ? "编辑个人资料" : "设置个人信息",
      pageSubtitle: isEdit
        ? "头像和昵称会保存到后端，换设备登录也会同步。"
        : "首次登录需要设置头像和昵称，后续会同步到云端。",
      fieldTip: isEdit ? "可随时修改" : "首次设置必填",
      submitText: isEdit ? "保存资料" : "完成设置",
      loading: isEdit
    });
    if (isEdit) {
      this.loadProfile();
    }
    this.refreshStaticAssets();
  },

  refreshStaticAssets() {
    Promise.all([cacheImage(DEFAULT_AVATAR_PATH), cacheImage(SETUP_BG_PATH)]).then(([avatarUrl, setupBg]) => {
      const shouldUseDefaultAvatar = !this.data.avatarTempPath && (!this.data.avatarUrl || this.data.avatarUrl === DEFAULT_AVATAR);
      this.setData({
        setupBg,
        avatarUrl: shouldUseDefaultAvatar ? avatarUrl : this.data.avatarUrl
      });
    });
  },

  async loadProfile() {
    try {
      const profile = await getProfile();
      this.setData({
        avatarUrl: profile.avatarUrl || DEFAULT_AVATAR,
        nickName: profile.nickName || ""
      });
    } catch (error) {
      wx.showToast({ title: error.message || "资料加载失败", icon: "none" });
    } finally {
      this.setData({ loading: false });
    }
  },

  handleChooseAvatar(event) {
    this.setData({
      avatarUrl: event.detail.avatarUrl,
      avatarTempPath: event.detail.avatarUrl
    });
  },

  handleNameInput(event) {
    this.setData({ nickName: event.detail.value });
  },

  async handleSubmit() {
    const nickName = String(this.data.nickName || "").trim();
    if (!nickName) {
      wx.showToast({ title: "请输入昵称", icon: "none" });
      return;
    }
    if (this.data.submitting) {
      return;
    }
    this.setData({ submitting: true });
    try {
      let avatarUrl = "";
      let displayUrl = this.data.avatarUrl;
      if (this.data.avatarTempPath && !this.data.avatarTempPath.startsWith("/assets")) {
        const uploaded = await uploadAvatar(this.data.avatarTempPath);
        avatarUrl = uploaded.avatarUrl;
        displayUrl = uploaded.displayUrl;
      }
      const profile = await updateProfile({ nickName, avatarUrl });
      const app = getApp();
      app.globalData.user = {
        ...(app.globalData.user || {}),
        ...profile,
        profileCompleted: true,
        avatarUrl: displayUrl || profile.avatarUrl
      };
      wx.setStorageSync("userProfile", app.globalData.user);
      wx.showToast({ title: "资料已保存", icon: "success" });
      setTimeout(() => {
        this.redirectBack();
      }, 450);
    } catch (error) {
      wx.showToast({ title: error.message || "保存失败", icon: "none" });
    } finally {
      this.setData({ submitting: false });
    }
  },

  redirectBack() {
    if (this.data.redirect && this.data.redirect !== "/pages/login/index") {
      wx.redirectTo({ url: this.data.redirect });
      return;
    }
    wx.redirectTo({ url: "/pages/profile/index" });
  }
});
