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
const { cacheImage, getCachedImageUrl } = require("../../utils/image-cache");

const PRELOAD_IMAGE_COUNT = 4;

Page({
  data: {
    glassMode: false,
    loading: true,
    categories: [],
    rawProducts: [],
    filteredProducts: [],
    products: [],
    productBatchSize: 12,
    loadingMore: false,
    hasMoreProducts: false,
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
    try {
      const categories = await this.loadCategories();
      const requestedCategoryId = Number(options.categoryId || 0);
      const requestedCategory = categories.find((category) => Number(category.id) === requestedCategoryId);
      const activeCategoryId = Number((requestedCategory || categories[0] || {}).id || 0);
      this.setData({ activeCategoryId });
      if (activeCategoryId) {
        await this.updateProducts(activeCategoryId);
      } else {
        this.setData({ rawProducts: [], filteredProducts: [], products: [], hasMoreProducts: false });
      }
    } finally {
      this.setData({ loading: false });
    }
  },

  onShow() {
    syncTheme(this);
    this.loadCartCount();
  },

  async loadCategories() {
    try {
      const categories = await getCategories();
      this.setData({ categories });
      return categories;
    } catch {
      this.setData({ categories: [] });
      wx.showToast({ title: "分类加载失败", icon: "none" });
      return [];
    }
  },

  async updateProducts(categoryId) {
    const requestId = (this.categoryRequestId || 0) + 1;
    this.categoryRequestId = requestId;
    try {
      const remoteProducts = await getProducts({ categoryId });
      if (requestId !== this.categoryRequestId) {
        return;
      }
      this.setData({ rawProducts: remoteProducts });
      this.applyFilters();
    } catch {
      if (requestId !== this.categoryRequestId) {
        return;
      }
      this.setData({
        rawProducts: [],
        filteredProducts: [],
        products: [],
        hasMoreProducts: false
      });
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

  noop() {},

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
    const filteredProducts = applyProductFilters(this.data.rawProducts, {
      sortMode: this.data.sortMode,
      priceRange: this.data.priceRange,
      onlyStock: this.data.onlyStock
    });
    const products = this.prepareVisibleProducts(filteredProducts.slice(0, this.data.productBatchSize));
    this.setData({
      filteredProducts,
      products,
      hasMoreProducts: products.length < filteredProducts.length,
      filterCount: getActiveFilterCount(this.data),
      activeFilterText: getOptionLabel(SORT_OPTIONS, this.data.sortMode) || "综合排序"
    });
    this.preloadProductImages(filteredProducts.slice(0, this.data.productBatchSize + PRELOAD_IMAGE_COUNT));
  },

  loadMoreProducts() {
    if (this.data.loadingMore || !this.data.hasMoreProducts) {
      return;
    }
    this.setData({ loadingMore: true });
    const nextProducts = this.data.filteredProducts.slice(
      0,
      this.data.products.length + this.data.productBatchSize
    );
    this.setData({
      products: this.prepareVisibleProducts(nextProducts),
      hasMoreProducts: nextProducts.length < this.data.filteredProducts.length,
      loadingMore: false
    });
    this.preloadProductImages(this.data.filteredProducts.slice(
      nextProducts.length,
      nextProducts.length + PRELOAD_IMAGE_COUNT
    ));
  },

  prepareVisibleProducts(products) {
    return products.map((product) => ({
      ...product,
      eagerImage: true,
      image: getCachedImageUrl(product.image)
    }));
  },

  preloadProductImages(products) {
    const uniqueImages = Array.from(new Set(
      products.map((product) => product && product.image).filter(Boolean)
    ));
    uniqueImages.forEach((imageUrl) => {
      cacheImage(imageUrl).then((cachedUrl) => {
        if (!cachedUrl || cachedUrl === imageUrl) {
          return;
        }
        const products = this.data.products.map((product) => (
          product.image === imageUrl ? { ...product, image: cachedUrl } : product
        ));
        const filteredProducts = this.data.filteredProducts.map((product) => (
          product.image === imageUrl ? { ...product, image: cachedUrl } : product
        ));
        this.setData({ products, filteredProducts });
      }).catch(() => {});
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
    const { product, quantity, skuId } = event.detail;
    try {
      await addCartItem(product.id, quantity, skuId);
      await this.loadCartCount();
      wx.showToast({ title: "已加入购物车", icon: "success" });
    } catch {
      wx.showToast({ title: "加入失败，请重试", icon: "none" });
    }
  }
});
