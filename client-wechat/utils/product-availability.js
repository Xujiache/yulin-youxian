const AVAILABLE = "available";
const OUT_OF_STOCK = "outOfStock";
const OFF_SHELF = "offShelf";

function productStatus(product) {
  if (!product) {
    return 1;
  }
  const value = product.productStatus !== undefined ? product.productStatus : product.status;
  const status = Number(value);
  return Number.isFinite(status) ? status : 1;
}

function getProductAvailability(product) {
  if (productStatus(product) !== 1) {
    return {
      state: OFF_SHELF,
      label: "商品已下架",
      shortLabel: "已下架",
      message: "该商品已下架"
    };
  }
  if (Number(product && product.stockQty) <= 0) {
    return {
      state: OUT_OF_STOCK,
      label: "库存不足",
      shortLabel: "售罄",
      message: "该商品库存不足"
    };
  }
  return {
    state: AVAILABLE,
    label: "",
    shortLabel: "",
    message: ""
  };
}

function isUnavailable(product) {
  return getProductAvailability(product).state !== AVAILABLE;
}

function sortAvailableFirst(products) {
  return [...(products || [])].sort((left, right) => (
    Number(isUnavailable(left)) - Number(isUnavailable(right))
  ));
}

module.exports = {
  AVAILABLE,
  OUT_OF_STOCK,
  OFF_SHELF,
  getProductAvailability,
  isUnavailable,
  sortAvailableFirst
};
