const { yuan } = require("../../utils/format");
const { isGlassModeEnabled } = require("../../utils/theme");
const { getProductAvailability } = require("../../utils/product-availability");

Component({
  properties: {
    product: {
      type: Object,
      value: {}
    },
    eagerImage: {
      type: Boolean,
      value: false
    }
  },

  data: {
    priceText: "0.00",
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
      this.setData({
        priceText: yuan(product.unitPrice),
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
    }
  }
});
