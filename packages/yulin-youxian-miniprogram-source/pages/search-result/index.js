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
    keyword: "",
    searchText: "",
    categories: [],
    categoryId: 0,
    rawProducts: [],
    products: [],
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
    const keyword = decodeURIComponent(options.keyword || "");
    this.setData({ keyword, searchText: keyword });
    try {
      await Promise.all([this.loadCategories(), this.loadProducts()]);
    } finally {
      this.setData({ loading: false });
    }
  },

  onShow() {
    this.loadCartCount();
  },

  goBack() {
    wx.navigateBack({ delta: 1 });
  },

  async loadCategories() {
    try {
      this.setData({ categories: await getCategories() });
    } catch {}
  },

  async loadProducts() {
    try {
      const params = {};
      if (this.data.keyword) {
        params.keyword = this.data.keyword;
      }
      if (this.data.categoryId) {
        params.categoryId = this.data.categoryId;
      }
      this.setData({ rawProducts: await getProducts(params) });
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

  handleSearchInput(event) {
    this.setData({ searchText: event.detail.value });
  },

  handleSearchConfirm() {
    const keyword = String(this.data.searchText || "").trim();
    if (!keyword) {
      wx.showToast({ title: "请输入搜索内容", icon: "none" });
      return;
    }
    this.setData({
      keyword,
      categoryId: 0,
      sortMode: "default",
      priceRange: "all",
      onlyStock: false
    });
    wx.setStorageSync("recentSearchKeywords", [
      keyword,
      ...(wx.getStorageSync("recentSearchKeywords") || []).filter((item) => item !== keyword)
    ].slice(0, 8));
    this.loadProducts();
  },

  openFilter() {
    this.setData({ filterVisible: true });
  },

  closeFilter() {
    this.setData({ filterVisible: false });
  },

  noop() {},

  async chooseCategory(event) {
    this.setData({ categoryId: Number(event.currentTarget.dataset.id || 0) });
    await this.loadProducts();
  },

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
      categoryId: 0,
      sortMode: "default",
      priceRange: "all",
      onlyStock: false
    });
    this.loadProducts();
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
