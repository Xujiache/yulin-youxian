const request = require("../utils/request");
const { normalizeAssetUrl, normalizeProduct } = require("./normalize");

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
    recommendedProducts: (home.recommendedProducts || []).map(normalizeProduct)
  };
}

function getCategories() {
  return request({
    url: "/api/wx/categories",
    skipAuth: true
  });
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
