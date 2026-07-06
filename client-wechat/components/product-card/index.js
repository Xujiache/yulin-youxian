const { yuan } = require("../../utils/format");

Component({
  properties: {
    product: {
      type: Object,
      value: {}
    }
  },

  data: {
    priceText: "0.00"
  },

  observers: {
    product(product) {
      this.setData({ priceText: yuan(product.unitPrice) });
    }
  },

  methods: {
    handleTap() {
      const id = this.properties.product.id;
      wx.navigateTo({ url: `/pages/product-detail/index?id=${id}` });
    },

    handleAdd() {
      this.triggerEvent("add", { product: this.properties.product });
    }
  }
});
