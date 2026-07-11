const { addCartItem, getCart } = require("../../api/cart");
const { getCategories, getProducts } = require("../../api/catalog");
const {
  SORT_OPTIONS,
  PRICE_OPTIONS,
  applyProductFilters,
  getOptionLabel,
  getActiveFilterCount
} = require("../../utils/product-filter");
const { requireCompleteProfile } = require("../../utils/auth-guard");
const { syncTheme } = require("../../utils/theme");

Page({
  data: {
    glassMode: false,
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
    draftCategoryId: 0,
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
    const keyword = decodeURIComponent(options.keyword || "");
    this.setData({ keyword, searchText: keyword });
    try {
      await Promise.all([this.loadCategories(), this.loadProducts()]);
    } finally {
      this.setData({ loading: false });
    }
  },

  onShow() {
    syncTheme(this);
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
      draftCategoryId: 0,
      sortMode: "default",
      draftSortMode: "default",
      priceRange: "all",
      draftPriceRange: "all",
      onlyStock: false,
      draftOnlyStock: false,
      filterCount: 0,
      draftFilterCount: 0
    });
    wx.setStorageSync("recentSearchKeywords", [
      keyword,
      ...(wx.getStorageSync("recentSearchKeywords") || []).filter((item) => item !== keyword)
    ].slice(0, 8));
    this.loadProducts();
  },

  openFilter() {
    const draftCategoryId = this.data.categoryId;
    const draftSortMode = this.data.sortMode;
    const draftPriceRange = this.data.priceRange;
    const draftOnlyStock = this.data.onlyStock;
    this.setData({
      filterVisible: true,
      draftCategoryId,
      draftSortMode,
      draftPriceRange,
      draftOnlyStock,
      draftFilterCount: getActiveFilterCount({
        categoryId: draftCategoryId,
        sortMode: draftSortMode,
        priceRange: draftPriceRange,
        onlyStock: draftOnlyStock
      })
    });
  },

  closeFilter() {
    this.setData({ filterVisible: false });
  },

  noop() {},

  async chooseCategory(event) {
    const draftCategoryId = Number(event.currentTarget.dataset.id || 0);
    this.setData({
      draftCategoryId,
      draftFilterCount: getActiveFilterCount({
        categoryId: draftCategoryId,
        sortMode: this.data.draftSortMode,
        priceRange: this.data.draftPriceRange,
        onlyStock: this.data.draftOnlyStock
      })
    });
  },

  chooseSort(event) {
    const draftSortMode = event.currentTarget.dataset.value;
    this.setData({
      draftSortMode,
      draftFilterCount: getActiveFilterCount({
        categoryId: this.data.draftCategoryId,
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
        categoryId: this.data.draftCategoryId,
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
        categoryId: this.data.draftCategoryId,
        sortMode: this.data.draftSortMode,
        priceRange: this.data.draftPriceRange,
        onlyStock: draftOnlyStock
      })
    });
  },

  resetFilter() {
    this.setData({
      draftCategoryId: 0,
      draftSortMode: "default",
      draftPriceRange: "all",
      draftOnlyStock: false,
      draftFilterCount: 0
    });
  },

  applyFilterAndClose() {
    this.setData({
      categoryId: this.data.draftCategoryId,
      sortMode: this.data.draftSortMode,
      priceRange: this.data.draftPriceRange,
      onlyStock: this.data.draftOnlyStock,
      filterCount: this.data.draftFilterCount,
      filterVisible: false
    }, () => this.loadProducts());
  },

  chooseQuickSort(event) {
    const sortMode = event.currentTarget.dataset.value || "default";
    const filterCount = getActiveFilterCount({
      categoryId: this.data.categoryId,
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
      categoryId: this.data.categoryId,
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
    } catch {
      wx.showToast({ title: "加入失败，请重试", icon: "none" });
    }
  }
});
