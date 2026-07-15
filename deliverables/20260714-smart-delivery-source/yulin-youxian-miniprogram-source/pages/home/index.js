const { getHome } = require("../../api/catalog");
const { addCartItem, getCart } = require("../../api/cart");
const { requireCompleteProfile } = require("../../utils/auth-guard");
const { cachedAssetUrl } = require("../../utils/image-cache");
const { syncTheme } = require("../../utils/theme");
Page({
    data: {
        glassMode: false,
        loading: true,
        storeLogoUrl: "",
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
        syncTheme(this);
        this.loadCartCount();
    },
    async loadHome() {
        try {
            const home = await getHome();
            const banners = (home.banners && home.banners.length ? home.banners : [
                {
                    title: home.bannerTitle || "今日新鲜到店",
                    subtitle: home.bannerSubtitle || "下单后由门店自行配送",
                    imageUrl: cachedAssetUrl("/assets/products/home-banner-1.jpg"),
                    linkType: "category",
                    linkTarget: "1"
                },
                {
                    title: "当日鲜选送到家",
                    subtitle: "蔬菜水果 · 微信支付更省心",
                    imageUrl: cachedAssetUrl("/assets/products/home-banner-2.jpg"),
                    linkType: "category",
                    linkTarget: "3"
                },
                {
                    title: "轻食蔬果组合",
                    subtitle: "黄瓜、番茄、草莓今日可选",
                    imageUrl: cachedAssetUrl("/assets/products/home-banner-3.jpg"),
                    linkType: "none",
                    linkTarget: ""
                }
            ]).filter((item) => item && item.imageUrl);
            this.setData({
                storeLogoUrl: home.logoUrl || cachedAssetUrl("/assets/products/store-logo.png"),
                banners,
                categories: (home.categories || []).slice(0, 5).map((item) => ({
                    ...item,
                    icon: item.iconUrl || item.imageUrl || "/assets/icons/category-default.svg"
                })),
                products: home.recommendedProducts || []
            });
        }
        catch {
            wx.showToast({ title: "请先启动后端服务", icon: "none" });
        }
        finally {
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
        }
        catch { }
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
        }
        catch {
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
