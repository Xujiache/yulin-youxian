const { addCartItem, getCart } = require("../../api/cart");
const { getCategories, getProducts } = require("../../api/catalog");
const {
  SORT_OPTIONS,
  PRICE_OPTIONS,
  applyProductFilters,
  getOptionLabel
} = require("../../utils/product-filter");
const { requireCompleteProfile } = require("../../utils/auth-guard");

Page({
  data: {
    loading: true,
    categories: [],
    rawProducts: [],
    products: [],
    activeCategoryId: 1,
    filterVisible: false,
    sortMode: "default",
    priceRange: "all",
    onlyStock: false,
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
    } finally {
      this.setData({ loading: false });
    }
  },

  onShow() {
    this.loadCartCount();
  },

  async loadCategories() {
    try {
      this.setData({ categories: await getCategories() });
    } catch {
      wx.showToast({ title: "分类加载失败", icon: "none" });
    }
  },

  async updateProducts(categoryId) {
    try {
      const remoteProducts = await getProducts({ categoryId });
      this.setData({ rawProducts: remoteProducts.length ? remoteProducts : await getProducts() });
      this.applyFilters();
    } catch {
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
    } catch {}
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
    this.setData({ filterVisible: true });
  },

  closeFilter() {
    this.setData({ filterVisible: false });
  },

  noop() {},

  chooseSort(event) {
    this.setData({ sortMode: event.currentTarget.dataset.value });
    this.applyFilters();
  },

  choosePrice(event) {
    this.setData({ priceRange: event.currentTarget.dataset.value });
    this.applyFilters();
  },

  handleStockChange(event) {
    this.setData({ onlyStock: event.detail.value });
    this.applyFilters();
  },

  resetFilter() {
    this.setData({
      sortMode: "default",
      priceRange: "all",
      onlyStock: false
    });
    this.applyFilters();
  },

  applyFilters() {
    const products = applyProductFilters(this.data.rawProducts, {
      sortMode: this.data.sortMode,
      priceRange: this.data.priceRange,
      onlyStock: this.data.onlyStock
    });
    this.setData({
      products,
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
    } catch {
      wx.showToast({ title: "加入失败，请重试", icon: "none" });
    }
  }
});
