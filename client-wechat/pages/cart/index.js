const { yuan, lineAmount } = require("../../utils/format");
const { getHome, getProduct } = require("../../api/catalog");
const {
  addCartItem,
  clearSelectedCartItems,
  deleteCartItem,
  getCart,
  replaceCartItemSku,
  setCartItemSelected,
  updateCartItem
} = require("../../api/cart");
const { requireCompleteProfile } = require("../../utils/auth-guard");
const { syncTheme } = require("../../utils/theme");
const { getProductAvailability } = require("../../utils/product-availability");

function decorateItems(items) {
  return items.map((item) => {
    const availability = getProductAvailability(item);
    return {
      ...item,
      amountText: yuan(lineAmount(item.unitPrice, item.quantity)),
      unavailable: Boolean(availability.label),
      availabilityLabel: availability.label,
      availabilityMessage: availability.message,
      canReselectSku: Boolean(item.skuId || item.reselectionRequired)
        && item.availabilityCode !== "PRODUCT_OFF_SHELF"
    };
  });
}

function unavailableNames(items) {
  const names = (items || []).map((item) => item.name).filter(Boolean);
  if (names.length <= 3) {
    return names.join("、");
  }
  return names.slice(0, 3).join("、") + "等 " + names.length + " 件商品";
}

Page({
  data: {
    glassMode: false,
    loading: true,
    items: [],
    recommendedProducts: [],
    selectedCount: 0,
    totalText: "0.00",
    checkoutPreparing: false,
    sheetVisible: false,
    selectedProduct: null,
    replacingCartItem: null,
    sheetInitialSkuId: 0,
    sheetInitialQuantity: 0,
    sheetActionMode: "cart"
  },

  onShow() {
    syncTheme(this);
    if (!requireCompleteProfile("/pages/cart/index")) {
      this.setData({ loading: false });
      return;
    }
    this.loadCart();
  },

  async loadCart() {
    try {
      const cart = await getCart();
      this.updateCart(cart.items || []);
      if (!(cart.items || []).length) {
        this.loadRecommendations();
      }
    } catch {
      this.setData({ items: [], selectedCount: 0, totalText: "0.00" });
      wx.showToast({ title: "购物车加载失败", icon: "none" });
    } finally {
      this.setData({ loading: false });
    }
  },

  updateCart(items) {
    const decoratedItems = decorateItems(items);
    const selectedItems = decoratedItems.filter((item) => item.selected);
    const total = selectedItems
      .filter((item) => !item.unavailable)
      .reduce((sum, item) => sum + lineAmount(item.unitPrice, item.quantity), 0);
    this.setData({
      items: decoratedItems,
      selectedCount: selectedItems.length,
      totalText: yuan(total)
    });
  },

  async loadRecommendations() {
    try {
      const home = await getHome();
      this.setData({
        recommendedProducts: (home.recommendedProducts || []).slice(0, 4)
      });
    } catch {
      this.setData({ recommendedProducts: [] });
    }
  },

  handleAddRecommended(event) {
    this.setData({
      selectedProduct: event.detail.product,
      replacingCartItem: null,
      sheetInitialSkuId: 0,
      sheetInitialQuantity: 0,
      sheetActionMode: "cart",
      sheetVisible: true
    });
  },

  handleCloseSheet() {
    this.setData({
      sheetVisible: false,
      replacingCartItem: null
    });
  },

  async handleConfirmSheet(event) {
    const { product, quantity, skuId, actionMode } = event.detail;
    try {
      if (actionMode === "replace" && this.data.replacingCartItem) {
        await replaceCartItemSku(this.data.replacingCartItem.id, skuId, quantity);
        wx.showToast({ title: "规格已更新", icon: "success" });
      } else {
        await addCartItem(product.id, quantity, skuId);
        wx.showToast({ title: "已加入购物车", icon: "success" });
      }
      await this.loadCart();
    } catch (error) {
      wx.showToast({ title: error.message || "操作失败，请重试", icon: "none" });
    }
  },

  async handleReselectSku(event) {
    const id = Number(event.currentTarget.dataset.id);
    const item = this.data.items.find((candidate) => candidate.id === id);
    if (!item || !item.canReselectSku) {
      return;
    }
    try {
      const product = await getProduct(item.productId);
      this.setData({
        selectedProduct: product,
        replacingCartItem: item,
        sheetInitialSkuId: Number(item.skuId || 0),
        sheetInitialQuantity: Number(item.quantity || 0),
        sheetActionMode: "replace",
        sheetVisible: true
      });
    } catch (error) {
      wx.showToast({ title: error.message || "商品规格加载失败", icon: "none" });
    }
  },

  async handleToggle(event) {
    const id = Number(event.currentTarget.dataset.id);
    const current = this.data.items.find((item) => item.id === id);
    if (current) {
      try {
        await setCartItemSelected(id, !current.selected);
        await this.loadCart();
        return;
      } catch {}
    }
    const items = this.data.items.map((item) => (
      item.id === id ? { ...item, selected: !item.selected } : item
    ));
    this.updateCart(items);
  },

  async handleQuantityChange(event) {
    const id = Number(event.currentTarget.dataset.id);
    const quantity = event.detail.value;
    const current = this.data.items.find((item) => item.id === id);
    if (current && current.unavailable) {
      wx.showToast({ title: current.availabilityMessage, icon: "none" });
      return;
    }
    try {
      await updateCartItem(id, quantity);
      await this.loadCart();
      return;
    } catch {}
    const items = this.data.items.map((item) => (
      item.id === id ? { ...item, quantity } : item
    ));
    this.updateCart(items);
  },

  async handleClearSelected() {
    if (!this.data.items.length) {
      return;
    }
    if (!this.data.selectedCount) {
      wx.showToast({ title: "请先选择商品", icon: "none" });
      return;
    }
    const result = await new Promise((resolve) => {
      wx.showModal({
        title: "清空已选",
        content: "确认清空已选商品吗？",
        success: resolve
      });
    });
    if (!result.confirm) {
      return;
    }
    try {
      await clearSelectedCartItems();
      await this.loadCart();
      wx.showToast({ title: "已清空", icon: "success" });
    } catch {
      wx.showToast({ title: "清空失败", icon: "none" });
    }
  },

  async handleCheckout() {
    if (!requireCompleteProfile("/pages/cart/index")) {
      return;
    }
    if (!this.data.selectedCount || this.data.checkoutPreparing) {
      if (!this.data.checkoutPreparing && !this.data.selectedCount) {
        wx.showToast({ title: "请先选择商品", icon: "none" });
      }
      return;
    }
    this.setData({ checkoutPreparing: true });
    try {
      const cart = await getCart();
      const items = decorateItems(cart.items || []);
      const selectedItems = items.filter((item) => item.selected);
      this.updateCart(cart.items || []);
      if (!selectedItems.length) {
        wx.showToast({ title: "请先选择商品", icon: "none" });
        return;
      }
      const unavailableItems = selectedItems.filter((item) => item.unavailable);
      if (!unavailableItems.length) {
        wx.navigateTo({ url: "/pages/checkout/index" });
        return;
      }
      const result = await new Promise((resolve) => {
        wx.showModal({
          title: "部分商品暂不可结算",
          content: unavailableNames(unavailableItems) + "存在已下架、库存不足或规格调整情况。是否去除这些商品后继续结算？",
          cancelText: "否",
          confirmText: "去除该商品",
          success: resolve
        });
      });
      if (!result.confirm) {
        return;
      }
      await Promise.all(unavailableItems.map((item) => deleteCartItem(item.id)));
      await this.loadCart();
      if (!this.data.selectedCount) {
        wx.showToast({ title: "异常商品已去除，请重新选择商品", icon: "none" });
        return;
      }
      wx.navigateTo({ url: "/pages/checkout/index" });
    } catch (error) {
      wx.showToast({ title: error.message || "结算检查失败，请重试", icon: "none" });
    } finally {
      this.setData({ checkoutPreparing: false });
    }
  },

  goHome() {
    wx.redirectTo({ url: "/pages/home/index" });
  }
});
