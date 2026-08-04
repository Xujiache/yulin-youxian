const { yuan, lineAmount } = require("../../utils/format");
const { getHome } = require("../../api/catalog");
const { addCartItem, clearSelectedCartItems, deleteCartItem, getCart, setCartItemSelected, updateCartItem } = require("../../api/cart");
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
      availabilityMessage: availability.message
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
    checkoutPreparing: false
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
      this.setData({
        items: decorateItems(cart.items || []),
        selectedCount: cart.selectedCount || 0,
        totalText: yuan(cart.totalAmount || 0)
      });
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
    const selectedItems = items.filter((item) => item.selected);
    const total = selectedItems.reduce((sum, item) => sum + lineAmount(item.unitPrice, item.quantity), 0);
    this.setData({
      items: decorateItems(items),
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

  async handleAddRecommended(event) {
    const product = event.detail.product;
    try {
      await addCartItem(product.id, product.minPurchaseQty || 1);
      await this.loadCart();
      wx.showToast({ title: "已加入购物车", icon: "success" });
    } catch {
      wx.showToast({ title: "加入失败，请重试", icon: "none" });
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
      this.setData({
        items,
        selectedCount: selectedItems.length,
        totalText: yuan(cart.totalAmount || 0)
      });
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
          content: unavailableNames(unavailableItems) + "存在库存不足或已下架情况。是否去除这些商品后继续结算？",
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
