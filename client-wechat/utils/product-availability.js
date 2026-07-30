const AVAILABLE = "available";
const OUT_OF_STOCK = "outOfStock";
const OFF_SHELF = "offShelf";
const SPEC_CHANGED = "specChanged";

function backendAvailability(product) {
  const code = product && product.availabilityCode;
  if (!code || code === "AVAILABLE") {
    return null;
  }
  const mappings = {
    PRODUCT_OFF_SHELF: {
      state: OFF_SHELF,
      label: "商品已下架",
      shortLabel: "已下架",
      message: "该商品已下架"
    },
    SKU_OFF_SHELF: {
      state: OFF_SHELF,
      label: "所选规格已下架",
      shortLabel: "规格下架",
      message: "所选规格已下架，请重新选择"
    },
    SKU_SELECTION_REQUIRED: {
      state: SPEC_CHANGED,
      label: "规格已调整",
      shortLabel: "重选规格",
      message: "商品规格已调整，请重新选择"
    },
    OUT_OF_STOCK: {
      state: OUT_OF_STOCK,
      label: "库存不足",
      shortLabel: "售罄",
      message: "所选规格库存不足"
    },
    INSUFFICIENT_STOCK: {
      state: OUT_OF_STOCK,
      label: "库存不足",
      shortLabel: "库存不足",
      message: "所选数量超过当前库存，请调整数量"
    }
  };
  const availability = mappings[code];
  if (!availability) {
    return null;
  }
  return {
    ...availability,
    message: product.availabilityMessage || availability.message
  };
}

function productStatus(product) {
  if (!product) {
    return 1;
  }
  const value = product.productStatus !== undefined ? product.productStatus : product.status;
  const status = Number(value);
  return Number.isFinite(status) ? status : 1;
}

function getProductAvailability(product) {
  const backend = backendAvailability(product);
  if (backend) {
    return backend;
  }
  if (productStatus(product) !== 1) {
    return {
      state: OFF_SHELF,
      label: "商品已下架",
      shortLabel: "已下架",
      message: "该商品已下架"
    };
  }
  if (product && product.skuEnabled && Number(product.availableSkuCount) <= 0) {
    const hasActiveSku = (product.skus || []).some((sku) => Number(sku.status) === 1);
    if (!hasActiveSku) {
      return {
        state: OFF_SHELF,
        label: "暂无可售规格",
        shortLabel: "已下架",
        message: "该商品的规格均已下架"
      };
    }
    return {
      state: OUT_OF_STOCK,
      label: "库存不足",
      shortLabel: "售罄",
      message: "该商品所有可售规格均库存不足"
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
  SPEC_CHANGED,
  getProductAvailability,
  isUnavailable,
  sortAvailableFirst
};
