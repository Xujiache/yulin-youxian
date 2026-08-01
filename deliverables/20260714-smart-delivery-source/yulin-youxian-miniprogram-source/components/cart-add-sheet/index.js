const { yuan, lineAmount } = require("../../utils/format");
const { isGlassModeEnabled } = require("../../utils/theme");
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
        amountText: "0.00",
        glassMode: false
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
