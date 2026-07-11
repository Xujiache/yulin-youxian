const request = require("../utils/request");
const { normalizeAssetUrl, normalizeProduct } = require("./normalize");

function normalizeCategory(category) {
  if (!category) {
    return category;
  }
  return {
    ...category,
    iconUrl: normalizeAssetUrl(category.iconUrl || category.imageUrl)
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
    recommendedProducts: (home.recommendedProducts || []).map(normalizeProduct)
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
  return products.map(normalizeProduct);
}

async function getProduct(id) {
  const product = await request({
    url: `/api/wx/products/${id}`,
    skipAuth: true
  });
  return normalizeProduct(product);
}

module.exports = {
  getHome,
  getCategories,
  getProducts,
  getProduct
};
