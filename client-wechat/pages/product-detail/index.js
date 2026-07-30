const { yuan, lineAmount } = require("../../utils/format");
const { getProduct } = require("../../api/catalog");
const { addCartItem, getCart } = require("../../api/cart");
const { requireCompleteProfile } = require("../../utils/auth-guard");
const { syncTheme } = require("../../utils/theme");
const { getProductAvailability } = require("../../utils/product-availability");

Page({
  data: {
    glassMode: false,
    loading: true,
    product: {
      id: 0,
      name: "",
      subtitle: "",
      badge: "",
      image: "",
      unitPrice: 0,
      stockQty: 0,
      minPurchaseQty: 1,
      stepQty: 1,
      saleUnit: ""
    },
    quantity: 1,
    amountText: "0.00",
    cartCount: 0,
    unavailable: false,
    availabilityLabel: "",
    availabilityMessage: "",
    maxPriceText: "0.00",
    sheetVisible: false,
    sheetActionMode: "cart"
  },

  async onLoad(options) {
    syncTheme(this);
    let product;
    try {
      product = await getProduct(Number(options.id));
    } catch {
      wx.showToast({ title: "商品加载失败", icon: "none" });
      this.setData({ loading: false });
      return;
    }
    const skuEnabled = Boolean(product.skuEnabled && (product.skus || []).length);
    const quantity = Number(product.minPurchaseQty || 1);
    const displayUnitPrice = skuEnabled ? product.minUnitPrice : product.unitPrice;
    const availability = getProductAvailability(product);
    this.setData({
      product,
      quantity,
      amountText: yuan(skuEnabled ? displayUnitPrice : lineAmount(displayUnitPrice, quantity)),
      maxPriceText: yuan(product.maxUnitPrice || displayUnitPrice),
      unavailable: Boolean(availability.label),
      availabilityLabel: availability.label,
      availabilityMessage: availability.message,
      loading: false
    });
  },

  onShow() {
    syncTheme(this);
    this.loadCartCount();
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

  onShareAppMessage() {
    const product = this.data.product || {};
    const productId = product.id || "";
    return {
      title: product.name ? `禹邻优鲜｜${product.name}` : "禹邻优鲜",
      path: `/pages/product-detail/index?id=${productId}`,
      imageUrl: product.image || ""
    };
  },

  onShareTimeline() {
    const product = this.data.product || {};
    const productId = product.id || "";
    return {
      title: product.name ? `禹邻优鲜｜${product.name}` : "禹邻优鲜",
      query: productId ? `id=${productId}` : "",
      imageUrl: product.image || ""
    };
  },

  handleQuantityChange(event) {
    const quantity = event.detail.value;
    this.setData({
      quantity,
      amountText: yuan(lineAmount(this.data.product.unitPrice, quantity))
    });
  },

  handleBack() {
    wx.navigateBack({
      fail() {
        wx.redirectTo({ url: "/pages/category/index" });
      }
    });
  },

  goCart() {
    wx.redirectTo({ url: "/pages/cart/index" });
  },

  canStartPurchase() {
    const availability = getProductAvailability(this.data.product);
    if (availability.label) {
      wx.showToast({ title: availability.message, icon: "none" });
      return false;
    }
    if (!requireCompleteProfile()) {
      return false;
    }
    return true;
  },

  openPurchaseSheet(actionMode) {
    if (!this.canStartPurchase()) {
      return false;
    }
    this.setData({
      sheetActionMode: actionMode,
      sheetVisible: true
    });
    return true;
  },

  handleChooseSpec() {
    this.openPurchaseSheet("cart");
  },

  handleCloseSheet() {
    this.setData({ sheetVisible: false });
  },

  async handleAddCart() {
    if (this.data.product.skuEnabled) {
      this.openPurchaseSheet("cart");
      return;
    }
    if (!this.canStartPurchase()) {
      return;
    }
    try {
      await addCartItem(this.data.product.id, this.data.quantity);
      await this.loadCartCount();
      wx.showToast({ title: "已加入购物车", icon: "success" });
    } catch (error) {
      wx.showToast({ title: error.message || "加入失败，请重试", icon: "none" });
    }
  },

  async handleBuyNow() {
    if (this.data.product.skuEnabled) {
      this.openPurchaseSheet("buy");
      return;
    }
    if (!this.canStartPurchase()) {
      return;
    }
    this.navigateBuyNow(null, this.data.quantity);
  },

  navigateBuyNow(skuId, quantity) {
    const productId = this.data.product.id;
    const skuQuery = skuId ? `&skuId=${skuId}` : "";
    wx.navigateTo({
      url: `/pages/checkout/index?buyNow=1&productId=${productId}${skuQuery}&quantity=${quantity}`
    });
  },

  async handleConfirmPurchase(event) {
    const { actionMode, quantity, skuId } = event.detail;
    if (actionMode === "buy") {
      this.navigateBuyNow(skuId, quantity);
      return;
    }
    try {
      await addCartItem(this.data.product.id, quantity, skuId);
      await this.loadCartCount();
      wx.showToast({ title: "已加入购物车", icon: "success" });
    } catch (error) {
      wx.showToast({ title: error.message || "加入失败，请重试", icon: "none" });
    }
  }
});
