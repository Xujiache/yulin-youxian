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
const { buildMinOrderState, normalizeMinOrderAmount } = require("../../utils/min-order");

function decorateItems(items) {
  return items.map((item) => {
    const availability = getProductAvailability(item);
    return {
      ...item,
      amountText: yuan(lineAmount(item.unitPrice, item.quantity)),
      unavailable: Boolean(availability.label),
      availabilityLabel: availability.label,
      availabilityMessage: availability.message,
      canReselectSku: Boolean(item.skuId || item.skuSelectionRequired || item.reselectionRequired)
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
    storeLogoUrl: "",
    items: [],
    recommendedProducts: [],
    selectedCount: 0,
    availableCount: 0,
    unavailableCount: 0,
    allAvailableSelected: false,
    totalText: "0.00",
    minOrderAmount: 0,
    minOrderText: "",
    minOrderTip: "",
    minOrderMet: true,
    checkoutLabel: "去结算",
    checkoutPreparing: false,
    deletingItemId: 0,
    clearingUnavailable: false,
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
      const [cart, home] = await Promise.all([
        getCart(),
        getHome().catch(() => null)
      ]);
      const minOrderAmount = home
        ? normalizeMinOrderAmount(home.minOrderAmount)
        : this.data.minOrderAmount;
      if (home && home.logoUrl) {
        this.setData({ storeLogoUrl: home.logoUrl });
      }
      this.updateCart(cart.items || [], minOrderAmount);
      if (!(cart.items || []).length) {
        this.loadRecommendations(home);
      }
    } catch {
      this.updateCart([], this.data.minOrderAmount);
      wx.showToast({ title: "购物车加载失败", icon: "none" });
    } finally {
      this.setData({ loading: false });
    }
  },

  updateCart(items, minOrderAmount = this.data.minOrderAmount) {
    const decoratedItems = decorateItems(items);
    const availableItems = decoratedItems.filter((item) => !item.unavailable);
    const unavailableItems = decoratedItems.filter((item) => item.unavailable);
    const selectedItems = availableItems.filter((item) => item.selected);
    const total = selectedItems
      .reduce((sum, item) => sum + lineAmount(item.unitPrice, item.quantity), 0);
    const minOrder = buildMinOrderState(total, minOrderAmount);
    this.setData({
      items: decoratedItems,
      availableCount: availableItems.length,
      unavailableCount: unavailableItems.length,
      selectedCount: selectedItems.length,
      allAvailableSelected: availableItems.length > 0 && selectedItems.length === availableItems.length,
      totalText: yuan(total),
      minOrderAmount: minOrder.minOrderAmount,
      minOrderText: minOrder.minOrderText,
      minOrderTip: minOrder.minOrderTip,
      minOrderMet: minOrder.minOrderMet,
      checkoutLabel: minOrder.checkoutLabel
    });
  },

  async loadRecommendations(homeData) {
    try {
      const home = homeData || await getHome();
      this.setData({
        recommendedProducts: (home.recommendedProducts || []).slice(0, 4),
        minOrderAmount: normalizeMinOrderAmount(home.minOrderAmount)
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
    if (current && current.unavailable) {
      wx.showToast({ title: current.availabilityMessage || "该商品暂不可结算", icon: "none" });
      return;
    }
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

  async handleToggleAll() {
    const availableItems = this.data.items.filter((item) => !item.unavailable);
    if (!availableItems.length) {
      return;
    }
    const selected = !this.data.allAvailableSelected;
    try {
      await Promise.all(availableItems.map((item) => setCartItemSelected(item.id, selected)));
      await this.loadCart();
    } catch {
      wx.showToast({ title: "全选状态更新失败", icon: "none" });
    }
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
      const selectedUnavailableItems = this.data.items.filter((item) => item.unavailable && item.selected);
      if (selectedUnavailableItems.length) {
        await Promise.all(selectedUnavailableItems.map((item) => setCartItemSelected(item.id, false)));
      }
      await clearSelectedCartItems();
      await this.loadCart();
      wx.showToast({ title: "已清空", icon: "success" });
    } catch {
      wx.showToast({ title: "清空失败", icon: "none" });
    }
  },

  async handleDeleteItem(event) {
    const id = Number(event.currentTarget.dataset.id);
    const item = this.data.items.find((candidate) => candidate.id === id);
    if (!item || this.data.deletingItemId) {
      return;
    }
    const result = await new Promise((resolve) => {
      wx.showModal({
        title: "删除商品",
        content: `确认从购物车删除“${item.name}”吗？`,
        confirmText: "删除",
        confirmColor: "#B54735",
        cancelText: "取消",
        success: resolve
      });
    });
    if (!result.confirm) {
      return;
    }
    this.setData({ deletingItemId: id });
    try {
      await deleteCartItem(id);
      await this.loadCart();
      wx.showToast({ title: "已删除", icon: "success" });
    } catch (error) {
      wx.showToast({ title: error.message || "删除失败，请重试", icon: "none" });
    } finally {
      this.setData({ deletingItemId: 0 });
    }
  },

  async handleClearUnavailable() {
    const unavailableItems = this.data.items.filter((item) => item.unavailable);
    if (!unavailableItems.length || this.data.clearingUnavailable) {
      return;
    }
    const result = await new Promise((resolve) => {
      wx.showModal({
        title: "清理失效商品",
        content: `确认删除这 ${unavailableItems.length} 件已下架、库存不足或规格变化的商品吗？`,
        confirmText: "全部删除",
        confirmColor: "#B54735",
        cancelText: "取消",
        success: resolve
      });
    });
    if (!result.confirm) {
      return;
    }
    this.setData({ clearingUnavailable: true });
    try {
      await Promise.all(unavailableItems.map((item) => deleteCartItem(item.id)));
      await this.loadCart();
      wx.showToast({ title: "失效商品已清理", icon: "success" });
    } catch (error) {
      wx.showToast({ title: error.message || "清理失败，请重试", icon: "none" });
    } finally {
      this.setData({ clearingUnavailable: false });
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
    if (!this.data.minOrderMet) {
      wx.showToast({
        title: this.data.minOrderTip || "未满起送价",
        icon: "none"
      });
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
      if (!this.data.minOrderMet) {
        wx.showToast({
          title: this.data.minOrderTip || "未满起送价",
          icon: "none"
        });
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
