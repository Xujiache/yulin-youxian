const SORT_OPTIONS = [
  { label: "综合排序", value: "default" },
  { label: "价格最低", value: "priceAsc" },
  { label: "价格最高", value: "priceDesc" },
  { label: "库存优先", value: "stockDesc" }
];

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
  const price = getNumber(product.unitPrice);
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
    if (onlyStock && getNumber(product.stockQty) <= 0) {
      return false;
    }
    return matchPrice(product, priceRange);
  });

  if (sortMode === "priceAsc") {
    return list.sort((a, b) => getNumber(a.unitPrice) - getNumber(b.unitPrice));
  }
  if (sortMode === "priceDesc") {
    return list.sort((a, b) => getNumber(b.unitPrice) - getNumber(a.unitPrice));
  }
  if (sortMode === "stockDesc") {
    return list.sort((a, b) => getNumber(b.stockQty) - getNumber(a.stockQty));
  }
  return list;
}

function getOptionLabel(options, value) {
  const option = (options || []).find((item) => item.value === value);
  return option ? option.label : "";
}

module.exports = {
  SORT_OPTIONS,
  PRICE_OPTIONS,
  applyProductFilters,
  getOptionLabel
};
