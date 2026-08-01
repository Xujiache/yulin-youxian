const SORT_OPTIONS = [
  { label: "综合排序", value: "default" },
  { label: "价格最低", value: "priceAsc" },
  { label: "价格最高", value: "priceDesc" },
  { label: "库存优先", value: "stockDesc" }
];
const { isUnavailable } = require("./product-availability");

const PRICE_OPTIONS = [
  { label: "全部价格", value: "all" },
  { label: "10元内", value: "lt1000" },
  { label: "10-30元", value: "1000-3000" },
  { label: "30元以上", value: "gt3000" }
];

function getNumber(value) {
  return Number(value || 0);
}

function matchPrice(product, priceRange) {
  const price = getNumber(product.skuEnabled ? product.minUnitPrice : product.unitPrice);
  if (priceRange === "lt1000") {
    return price < 1000;
  }
  if (priceRange === "1000-3000") {
    return price >= 1000 && price <= 3000;
  }
  if (priceRange === "gt3000") {
    return price > 3000;
  }
  return true;
}

function applyProductFilters(products, filters) {
  const sortMode = filters.sortMode || "default";
  const priceRange = filters.priceRange || "all";
  const onlyStock = Boolean(filters.onlyStock);
  const list = (products || []).filter((product) => {
    if (onlyStock && isUnavailable(product)) {
      return false;
    }
    return matchPrice(product, priceRange);
  });

  return list.sort((left, right) => {
    const availabilityOrder = Number(isUnavailable(left)) - Number(isUnavailable(right));
    if (availabilityOrder) {
      return availabilityOrder;
    }
    const leftPrice = getNumber(left.skuEnabled ? left.minUnitPrice : left.unitPrice);
    const rightPrice = getNumber(right.skuEnabled ? right.minUnitPrice : right.unitPrice);
    if (sortMode === "priceAsc") {
      return leftPrice - rightPrice;
    }
    if (sortMode === "priceDesc") {
      return rightPrice - leftPrice;
    }
    if (sortMode === "stockDesc") {
      return getNumber(right.stockQty) - getNumber(left.stockQty);
    }
    return 0;
  });
}

function getOptionLabel(options, value) {
  const option = (options || []).find((item) => item.value === value);
  return option ? option.label : "";
}

function getActiveFilterCount(filters) {
  const current = filters || {};
  let count = 0;
  if (current.sortMode && current.sortMode !== "default") {
    count += 1;
  }
  if (current.priceRange && current.priceRange !== "all") {
    count += 1;
  }
  if (current.onlyStock) {
    count += 1;
  }
  if (current.categoryId) {
    count += 1;
  }
  return count;
}

module.exports = {
  SORT_OPTIONS,
  PRICE_OPTIONS,
  applyProductFilters,
  getOptionLabel,
  getActiveFilterCount
};
