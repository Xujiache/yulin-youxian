const { API_BASE_URL } = require("../utils/config");

function normalizeAssetUrl(url) {
  if (!url) {
    return url;
  }
  if (/^https?:\/\//.test(url)) {
    return url;
  }
  if (url.startsWith("/assets/") || url.startsWith("/uploads/")) {
    const app = getApp();
    const baseUrl = (app.globalData && app.globalData.apiBaseUrl) || API_BASE_URL;
    return baseUrl ? `${baseUrl}${url}` : url;
  }
  return url;
}

function normalizeProduct(product) {
  if (!product) {
    return product;
  }
  const imageUrl = normalizeAssetUrl(product.imageUrl || product.image);
  return {
    ...product,
    imageUrl,
    image: imageUrl
  };
}

function normalizeCartItem(item) {
  if (!item) {
    return item;
  }
  const imageUrl = normalizeAssetUrl(item.imageUrl || item.image);
  return {
    ...item,
    imageUrl,
    image: imageUrl,
    name: item.name || item.productName
  };
}

function normalizeOrderItem(item) {
  if (!item) {
    return item;
  }
  const imageUrl = normalizeAssetUrl(item.imageUrl || item.image);
  return {
    ...item,
    imageUrl,
    image: imageUrl,
    name: item.name || item.productName
  };
}

function normalizeOrder(order) {
  if (!order) {
    return order;
  }
  return {
    ...order,
    images: order.images || []
  };
}

module.exports = {
  normalizeAssetUrl,
  normalizeProduct,
  normalizeCartItem,
  normalizeOrderItem,
  normalizeOrder
};
