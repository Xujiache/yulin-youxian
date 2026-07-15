const { yuan, lineAmount } = require("../../utils/format");
const { getHome } = require("../../api/catalog");
const { addCartItem, clearSelectedCartItems, getCart, setCartItemSelected, updateCartItem } = require("../../api/cart");
const { requireCompleteProfile } = require("../../utils/auth-guard");
const { syncTheme } = require("../../utils/theme");
function decorateItems(items) {
    return items.map((item) => ({
        ...item,
        amountText: yuan(lineAmount(item.unitPrice, item.quantity))
    }));
}
Page({
    data: {
        glassMode: false,
        loading: true,
        items: [],
        recommendedProducts: [],
        selectedCount: 0,
        totalText: "0.00"
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
        }
        catch {
            this.setData({ items: [], selectedCount: 0, totalText: "0.00" });
            wx.showToast({ title: "购物车加载失败", icon: "none" });
        }
        finally {
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
        }
        catch {
            this.setData({ recommendedProducts: [] });
        }
    },
    async handleAddRecommended(event) {
        const product = event.detail.product;
        try {
            await addCartItem(product.id, product.minPurchaseQty || 1);
            await this.loadCart();
            wx.showToast({ title: "已加入购物车", icon: "success" });
        }
        catch {
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
            }
            catch { }
        }
        const items = this.data.items.map((item) => (item.id === id ? { ...item, selected: !item.selected } : item));
        this.updateCart(items);
    },
    async handleQuantityChange(event) {
        const id = Number(event.currentTarget.dataset.id);
        const quantity = event.detail.value;
        try {
            await updateCartItem(id, quantity);
            await this.loadCart();
            return;
        }
        catch { }
        const items = this.data.items.map((item) => (item.id === id ? { ...item, quantity } : item));
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
        }
        catch {
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
    },
    goHome() {
        wx.redirectTo({ url: "/pages/home/index" });
    }
});
