const { yuan, lineAmount } = require("../../utils/format");

Component({
  properties: {
    visible: {
      type: Boolean,
      value: false
    },
    product: {
      type: Object,
      value: {}
    }
  },

  data: {
    quantity: 1,
    unitPriceText: "0.00",
    amountText: "0.00"
  },

  observers: {
    "visible, product": function (visible, product) {
      if (!visible || !product || !product.id) {
        return;
      }

      const quantity = Number(product.minPurchaseQty || 1);
      const amount = lineAmount(product.unitPrice, quantity);
      this.setData({
        quantity,
        unitPriceText: yuan(product.unitPrice),
        amountText: yuan(amount)
      });
    }
  },

  methods: {
    handleClose() {
      this.triggerEvent("close");
    },

    handleQuantityChange(event) {
      const quantity = event.detail.value;
      const amount = lineAmount(this.properties.product.unitPrice, quantity);
      this.setData({
        quantity,
        amountText: yuan(amount)
      });
    },

    handleConfirm() {
      this.triggerEvent("confirm", {
        product: this.properties.product,
        quantity: this.data.quantity
      });
      this.handleClose();
    }
  }
});
