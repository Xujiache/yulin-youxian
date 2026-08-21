const { yuan } = require("../../utils/format");
const { isGlassModeEnabled } = require("../../utils/theme");
const { getProductAvailability } = require("../../utils/product-availability");

Component({
  properties: {
    product: {
      type: Object,
      value: {}
    },
    cartQuantity: {
      type: Number,
      value: 0
    },
    eagerImage: {
      type: Boolean,
      value: false
    }
  },

  data: {
    priceText: "0.00",
    priceFrom: false,
    priceSaleUnit: "",
    glassMode: false,
    unavailable: false,
    availabilityLabel: "",
    availabilityShortLabel: "",
    availabilityMessage: ""
  },

  lifetimes: {
    attached() {
      this.setData({ glassMode: isGlassModeEnabled() });
    }
  },

  pageLifetimes: {
    show() {
      this.setData({ glassMode: isGlassModeEnabled() });
    }
  },

  observers: {
    product(product) {
      const availability = getProductAvailability(product);
      const minPriceSku = product.skuEnabled
        ? (product.skus || [])
            .filter((sku) => Number(sku.status) === 1)
            .sort((left, right) => Number(left.unitPrice) - Number(right.unitPrice))[0]
        : null;
      this.setData({
        priceText: yuan(product.minUnitPrice || product.unitPrice),
        priceFrom: Boolean(product.skuEnabled && Number(product.maxUnitPrice) > Number(product.minUnitPrice)),
        priceSaleUnit: minPriceSku ? minPriceSku.saleUnit : product.saleUnit,
        unavailable: Boolean(availability.label),
        availabilityLabel: availability.label,
        availabilityShortLabel: availability.shortLabel,
        availabilityMessage: availability.message
      });
    }
  },

  methods: {
    handleTap() {
      const id = this.properties.product.id;
      wx.navigateTo({ url: `/pages/product-detail/index?id=${id}` });
    },

    handleAdd() {
      if (this.data.unavailable) {
        wx.showToast({ title: this.data.availabilityMessage, icon: "none" });
        return;
      }
      this.triggerEvent("add", { product: this.properties.product });
    },

    handleMinus() {
      if (this.data.unavailable) {
        return;
      }
      this.triggerEvent("minus", { product: this.properties.product });
    }
  }
});
