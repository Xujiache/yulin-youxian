const { yuan, lineAmount } = require("../../utils/format");
const { clearSelectedCartItems, getCart, setCartItemSelected, updateCartItem } = require("../../api/cart");
const { requireCompleteProfile } = require("../../utils/auth-guard");

function decorateItems(items) {
  return items.map((item) => ({
    ...item,
    amountText: yuan(lineAmount(item.unitPrice, item.quantity))
  }));
}

Page({
  data: {
    loading: true,
    items: [],
    selectedCount: 0,
    totalText: "0.00"
  },

  onLoad() {
    if (!requireCompleteProfile("/pages/cart/index")) {
      this.setData({ loading: false });
      return;
    }
    this.loadCart();
  },

  onShow() {
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

  handleCheckout() {
    if (!requireCompleteProfile("/pages/cart/index")) {
      return;
    }
    if (!this.data.selectedCount) {
      wx.showToast({ title: "请先选择商品", icon: "none" });
      return;
    }
    wx.navigateTo({ url: "/pages/checkout/index" });
  }
});
