const { API_BASE_URL } = require("../utils/config");
const { getCachedImageUrl } = require("../utils/image-cache");

function numberOr(value, fallback) {
  const number = Number(value);
  return Number.isFinite(number) ? number : fallback;
}

// 带查询串的资源地址是服务端签发的限时票据（送达凭证照片就走这条路）。
// 本地缓存按完整 URL 做键，签名每次都不一样，缓存必然全部落空还会把私密照片留在本地，
// 所以这类地址只拼域名、不进缓存。
function isSignedAssetUrl(url) {
  return url.includes("?");
}

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
    const normalized = baseUrl ? `${baseUrl}${url}` : url;
    return isSignedAssetUrl(normalized) ? normalized : getCachedImageUrl(normalized);
  }
  return isSignedAssetUrl(url) ? url : getCachedImageUrl(url);
}

function normalizeProduct(product) {
  if (!product) {
    return product;
  }
  const imageUrl = normalizeAssetUrl(product.imageUrl || product.image);
  const specGroups = (product.specGroups || []).map((group) => ({
    ...group,
    sortOrder: numberOr(group.sortOrder, 0),
    options: (group.options || []).map((option) => ({
      ...option,
      imageUrl: normalizeAssetUrl(option.imageUrl || ""),
      sortOrder: numberOr(option.sortOrder, 0)
    }))
  }));
  const skus = (product.skus || []).map((sku) => ({
    ...sku,
    unitPrice: numberOr(sku.unitPrice, 0),
    stockQty: numberOr(sku.stockQty, 0),
    minPurchaseQty: numberOr(sku.minPurchaseQty, 1),
    stepQty: numberOr(sku.stepQty, 1),
    status: numberOr(sku.status, 1),
    sortOrder: numberOr(sku.sortOrder, 0),
    defaultSku: Boolean(sku.defaultSku),
    optionValueIds: (sku.optionValueIds || []).map(String),
    imageUrl: normalizeAssetUrl(sku.imageUrl || imageUrl),
    image: normalizeAssetUrl(sku.imageUrl || imageUrl)
  }));
  return {
    ...product,
    skuEnabled: Boolean(product.skuEnabled),
    minPurchaseQty: numberOr(product.minPurchaseQty, 1),
    stockQty: numberOr(product.stockQty, 0),
    stepQty: numberOr(product.stepQty, 1),
    minUnitPrice: numberOr(product.minUnitPrice, numberOr(product.unitPrice, 0)),
    maxUnitPrice: numberOr(product.maxUnitPrice, numberOr(product.unitPrice, 0)),
    availableSkuCount: numberOr(product.availableSkuCount, 0),
    saleUnit: product.saleUnit || "",
    specGroups,
    skus,
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
    quantity: numberOr(item.quantity, 1),
    minPurchaseQty: numberOr(item.minPurchaseQty, 1),
    stockQty: numberOr(item.stockQty, 9999),
    productStatus: numberOr(item.productStatus !== undefined ? item.productStatus : item.status, 1),
    status: numberOr(item.productStatus !== undefined ? item.productStatus : item.status, 1),
    stepQty: numberOr(item.stepQty, 1),
    saleUnit: item.saleUnit || "",
    imageUrl,
    image: imageUrl,
    name: item.name || item.productName,
    skuId: item.skuId === null || item.skuId === undefined ? null : numberOr(item.skuId, null),
    skuStatus: item.skuStatus === null || item.skuStatus === undefined ? null : numberOr(item.skuStatus, 1),
    skuSelectionRequired: Boolean(item.skuSelectionRequired),
    specificationText: item.specificationText || "",
    skuCode: item.skuCode || "",
    availabilityCode: item.availabilityCode || "",
    availabilityMessage: item.availabilityMessage || ""
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
    name: item.name || item.productName,
    skuId: item.skuId === null || item.skuId === undefined ? null : numberOr(item.skuId, null),
    specificationText: item.specificationText || "",
    skuCode: item.skuCode || ""
  };
}

function normalizeOrder(order) {
  if (!order) {
    return order;
  }
  return {
    ...order,
    images: (order.images || []).map(normalizeAssetUrl)
  };
}

// 赠品图和奖项图都来自 /assets/products，未拼接域名时真机必破图（该目录被 packOptions.ignore 排除出包）
function normalizeGift(gift) {
  if (!gift) {
    return gift;
  }
  return {
    ...gift,
    imageUrl: normalizeAssetUrl(gift.imageUrl || "")
  };
}

function normalizeGifts(gifts) {
  return (Array.isArray(gifts) ? gifts : []).map(normalizeGift);
}

function normalizePrizeResult(result) {
  if (!result) {
    return result;
  }
  return {
    ...result,
    imageUrl: normalizeAssetUrl(result.imageUrl || ""),
    gifts: normalizeGifts(result.gifts)
  };
}

function normalizeRefund(refund) {
  if (!refund) {
    return refund;
  }
  return {
    ...refund,
    evidenceImages: (refund.evidenceImages || []).map(normalizeAssetUrl)
  };
}

module.exports = {
  normalizeAssetUrl,
  normalizeProduct,
  normalizeCartItem,
  normalizeGift,
  normalizeGifts,
  normalizeOrderItem,
  normalizeOrder,
  normalizePrizeResult,
  normalizeRefund
};
