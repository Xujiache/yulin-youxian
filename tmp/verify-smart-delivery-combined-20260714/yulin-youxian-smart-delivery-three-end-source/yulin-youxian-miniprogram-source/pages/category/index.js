const { addCartItem, getCart } = require("../../api/cart");
const { getCategories, getProducts } = require("../../api/catalog");
const { SORT_OPTIONS, PRICE_OPTIONS, applyProductFilters, getOptionLabel, getActiveFilterCount } = require("../../utils/product-filter");
const { requireCompleteProfile } = require("../../utils/auth-guard");
const { syncTheme } = require("../../utils/theme");
Page({
    data: {
        glassMode: false,
        loading: true,
        categories: [],
        rawProducts: [],
        products: [],
        activeCategoryId: 1,
        filterVisible: false,
        sortMode: "default",
        priceRange: "all",
        onlyStock: false,
        draftSortMode: "default",
        draftPriceRange: "all",
        draftOnlyStock: false,
        filterCount: 0,
        draftFilterCount: 0,
        sortOptions: SORT_OPTIONS,
        priceOptions: PRICE_OPTIONS,
        activeFilterText: "综合排序",
        sheetVisible: false,
        selectedProduct: null,
        cartCount: 0
    },
    async onLoad(options) {
        const activeCategoryId = Number(options.categoryId || 1);
        this.setData({ activeCategoryId });
        try {
            await this.loadCategories();
            await this.updateProducts(activeCategoryId);
        }
        finally {
            this.setData({ loading: false });
        }
    },
    onShow() {
        syncTheme(this);
        this.loadCartCount();
    },
    async loadCategories() {
        try {
            this.setData({ categories: await getCategories() });
        }
        catch {
            wx.showToast({ title: "分类加载失败", icon: "none" });
        }
    },
    async updateProducts(categoryId) {
        try {
            const remoteProducts = await getProducts({ categoryId });
            this.setData({ rawProducts: remoteProducts.length ? remoteProducts : await getProducts() });
            this.applyFilters();
        }
        catch {
            this.setData({ rawProducts: [], products: [] });
            wx.showToast({ title: "商品加载失败", icon: "none" });
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
    async handleCategory(event) {
        const activeCategoryId = Number(event.currentTarget.dataset.id);
        this.setData({ activeCategoryId });
        await this.updateProducts(activeCategoryId);
    },
    goSearch() {
        wx.navigateTo({ url: "/pages/search/index" });
    },
    openFilter() {
        const draftSortMode = this.data.sortMode;
        const draftPriceRange = this.data.priceRange;
        const draftOnlyStock = this.data.onlyStock;
        this.setData({
            filterVisible: true,
            draftSortMode,
            draftPriceRange,
            draftOnlyStock,
            draftFilterCount: getActiveFilterCount({
                sortMode: draftSortMode,
                priceRange: draftPriceRange,
                onlyStock: draftOnlyStock
            })
        });
    },
    closeFilter() {
        this.setData({ filterVisible: false });
    },
    noop() { },
    chooseSort(event) {
        const draftSortMode = event.currentTarget.dataset.value;
        this.setData({
            draftSortMode,
            draftFilterCount: getActiveFilterCount({
                sortMode: draftSortMode,
                priceRange: this.data.draftPriceRange,
                onlyStock: this.data.draftOnlyStock
            })
        });
    },
    choosePrice(event) {
        const draftPriceRange = event.currentTarget.dataset.value;
        this.setData({
            draftPriceRange,
            draftFilterCount: getActiveFilterCount({
                sortMode: this.data.draftSortMode,
                priceRange: draftPriceRange,
                onlyStock: this.data.draftOnlyStock
            })
        });
    },
    handleStockChange(event) {
        const draftOnlyStock = Boolean(event.detail.value);
        this.setData({
            draftOnlyStock,
            draftFilterCount: getActiveFilterCount({
                sortMode: this.data.draftSortMode,
                priceRange: this.data.draftPriceRange,
                onlyStock: draftOnlyStock
            })
        });
    },
    resetFilter() {
        this.setData({
            draftSortMode: "default",
            draftPriceRange: "all",
            draftOnlyStock: false,
            draftFilterCount: 0
        });
    },
    applyFilterAndClose() {
        this.setData({
            sortMode: this.data.draftSortMode,
            priceRange: this.data.draftPriceRange,
            onlyStock: this.data.draftOnlyStock,
            filterCount: this.data.draftFilterCount,
            filterVisible: false
        }, () => this.applyFilters());
    },
    chooseQuickSort(event) {
        const sortMode = event.currentTarget.dataset.value || "default";
        const filterCount = getActiveFilterCount({
            sortMode,
            priceRange: this.data.priceRange,
            onlyStock: this.data.onlyStock
        });
        this.setData({
            sortMode,
            draftSortMode: sortMode,
            filterCount,
            draftFilterCount: filterCount
        }, () => this.applyFilters());
    },
    togglePriceSort() {
        const sortMode = this.data.sortMode === "priceAsc" ? "priceDesc" : "priceAsc";
        const filterCount = getActiveFilterCount({
            sortMode,
            priceRange: this.data.priceRange,
            onlyStock: this.data.onlyStock
        });
        this.setData({
            sortMode,
            draftSortMode: sortMode,
            filterCount,
            draftFilterCount: filterCount
        }, () => this.applyFilters());
    },
    applyFilters() {
        const products = applyProductFilters(this.data.rawProducts, {
            sortMode: this.data.sortMode,
            priceRange: this.data.priceRange,
            onlyStock: this.data.onlyStock
        });
        this.setData({
            products,
            filterCount: getActiveFilterCount(this.data),
            activeFilterText: getOptionLabel(SORT_OPTIONS, this.data.sortMode) || "综合排序"
        });
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
    }
});
