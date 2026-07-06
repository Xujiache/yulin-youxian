const { getHome } = require("../../api/catalog");
const { addCartItem, getCart } = require("../../api/cart");
const { requireCompleteProfile } = require("../../utils/auth-guard");
const { cachedAssetUrl } = require("../../utils/image-cache");

Page({
  data: {
    loading: true,
    banners: [],
    categories: [],
    products: [],
    sheetVisible: false,
    selectedProduct: null,
    cartCount: 0
  },

  onLoad() {
    this.loadHome();
  },

  onShow() {
    this.loadCartCount();
  },

  async loadHome() {
    try {
      const home = await getHome();
      const banners = (home.banners && home.banners.length ? home.banners : [
        {
          title: home.bannerTitle || "今日新鲜到店",
          subtitle: home.bannerSubtitle || "下单后由门店自行配送",
          imageUrl: cachedAssetUrl("/assets/products/hero.png"),
          linkType: "none",
          linkTarget: ""
        }
      ]).filter((item) => item && item.imageUrl);
      this.setData({
        banners,
        categories: (home.categories || []).slice(0, 5).map((item) => ({
          ...item,
          icon: item.imageUrl || "/assets/icons/category-default.svg"
        })),
        products: home.recommendedProducts || []
      });
    } catch {
      wx.showToast({ title: "请先启动后端服务", icon: "none" });
    } finally {
      this.setData({ loading: false });
    }
  },

  async loadCartCount() {
    const app = getApp();
    if (!app.globalData.authToken && !wx.getStorageSync("authToken")) {
      this.setData({ cartCount: 0 });
      return;
    }
    try {
      const cart = await getCart();
      this.setData({ cartCount: (cart.items || []).length });
    } catch {}
  },

  handleAdd(event) {
    if (!requireCompleteProfile()) {
      return;
    }
    this.setData({
      selectedProduct: event.detail.product,
      sheetVisible: true
    });
  },

  handleCloseSheet() {
    this.setData({ sheetVisible: false });
  },

  async handleConfirmAdd(event) {
    if (!requireCompleteProfile()) {
      return;
    }
    const { product, quantity } = event.detail;
    try {
      await addCartItem(product.id, quantity);
      await this.loadCartCount();
      wx.showToast({ title: "已加入购物车", icon: "success" });
    } catch {
      wx.showToast({ title: "加入失败，请重试", icon: "none" });
    }
  },

  goSearch() {
    wx.navigateTo({ url: "/pages/search/index" });
  },

  goCategory(event) {
    const id = event.currentTarget.dataset.id;
    wx.redirectTo({ url: `/pages/category/index?categoryId=${id}` });
  },

  handleBannerTap(event) {
    const { linkType, linkTarget } = event.currentTarget.dataset;
    if (!linkType || linkType === "none" || !linkTarget) {
      return;
    }
    if (linkType === "product") {
      wx.navigateTo({ url: `/pages/product-detail/index?id=${linkTarget}` });
      return;
    }
    if (linkType === "category") {
      wx.redirectTo({ url: `/pages/category/index?categoryId=${linkTarget}` });
    }
  }
});
