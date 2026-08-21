const request = require("../utils/request");
const { normalizeAssetUrl, normalizeProduct } = require("./normalize");
const { filterListedProducts, isProductListed } = require("../utils/product-availability");

const MISSING_CATEGORY_ASSET_PATHS = new Set([
  "/assets/products/category-fruit-3d.png",
  "/assets/products/category-vegetable-3d.png"
]);

function categoryAssetPath(url) {
  if (!url) {
    return "";
  }
  return String(url).replace(/^https?:\/\/[^/]+/i, "");
}

function normalizeCategory(category) {
  if (!category) {
    return category;
  }
  const sourceIconUrl = category.iconUrl || category.imageUrl || "";
  const iconUrl = MISSING_CATEGORY_ASSET_PATHS.has(categoryAssetPath(sourceIconUrl))
    ? "/assets/icons/category-default.svg"
    : normalizeAssetUrl(sourceIconUrl);
  return {
    ...category,
    iconUrl
  };
}

async function getHome() {
  const home = await request({
    url: "/api/wx/home",
    skipAuth: true
  });
  return {
    ...home,
    logoUrl: normalizeAssetUrl(home.logoUrl),
    banners: (home.banners || []).map((banner) => ({
      ...banner,
      imageUrl: normalizeAssetUrl(banner.imageUrl)
    })),
    categories: (home.categories || []).map(normalizeCategory),
    recommendedProducts: filterListedProducts((home.recommendedProducts || []).map(normalizeProduct))
  };
}

async function getCategories() {
  const categories = await request({
    url: "/api/wx/categories",
    skipAuth: true
  });
  return (categories || []).map(normalizeCategory);
}

async function getProducts(params = {}) {
  const products = await request({
    url: "/api/wx/products",
    data: params,
    skipAuth: true
  });
  return filterListedProducts(products.map(normalizeProduct));
}

async function getProduct(id) {
  const product = await request({
    url: `/api/wx/products/${id}`,
    skipAuth: true
  });
  const normalized = normalizeProduct(product);
  if (!isProductListed(normalized)) {
    const error = new Error("商品已下架");
    error.statusCode = 404;
    throw error;
  }
  return normalized;
}

module.exports = {
  getHome,
  getCategories,
  getProducts,
  getProduct
};
