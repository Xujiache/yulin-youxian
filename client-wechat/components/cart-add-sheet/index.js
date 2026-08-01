const { yuan, lineAmount } = require("../../utils/format");
const { isGlassModeEnabled } = require("../../utils/theme");
const { getProductAvailability } = require("../../utils/product-availability");

function sameCombination(left, right) {
  const leftIds = [...(left || [])].map(String).sort();
  const rightIds = [...(right || [])].map(String).sort();
  return leftIds.length === rightIds.length && leftIds.every((id, index) => id === rightIds[index]);
}

function skuPurchasable(sku) {
  return Boolean(sku && Number(sku.status) === 1 && Number(sku.stockQty) > 0);
}

Component({
  properties: {
    visible: {
      type: Boolean,
      value: false
    },
    product: {
      type: Object,
      value: {}
    },
    actionMode: {
      type: String,
      value: "cart"
    },
    initialSkuId: {
      type: Number,
      value: 0
    },
    initialQuantity: {
      type: Number,
      value: 0
    }
  },

  data: {
    quantity: 1,
    unitPriceText: "0.00",
    amountText: "0.00",
    glassMode: false,
    unavailable: false,
    availabilityLabel: "",
    availabilityMessage: "",
    skuEnabled: false,
    displayGroups: [],
    selectedOptionIds: [],
    activeSku: null,
    activeSkuId: 0,
    displayImage: "",
    displaySaleUnit: "",
    displayStockQty: 0,
    displayMinPurchaseQty: 1,
    displayStepQty: 1,
    selectionText: "",
    confirmLabel: "加入购物车",
    selectionIncomplete: false
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
    "visible, product, actionMode, initialSkuId, initialQuantity": function (
      visible,
      product
    ) {
      if (!visible || !product || !product.id) {
        return;
      }
      this.initializeSelection();
    }
  },

  methods: {
    initializeSelection() {
      const product = this.properties.product || {};
      const skuEnabled = Boolean(product.skuEnabled && (product.skus || []).length);
      const confirmLabels = {
        buy: "立即购买",
        replace: "确认更换",
        cart: "加入购物车"
      };
      if (!skuEnabled) {
        const quantity = Number(this.properties.initialQuantity || product.minPurchaseQty || 1);
        const availability = getProductAvailability(product);
        this.setData({
          skuEnabled: false,
          displayGroups: [],
          selectedOptionIds: [],
          activeSku: null,
          activeSkuId: 0,
          quantity,
          unitPriceText: yuan(product.unitPrice),
          amountText: yuan(lineAmount(product.unitPrice, quantity)),
          displayImage: product.image,
          displaySaleUnit: product.saleUnit || "",
          displayStockQty: Number(product.stockQty || 0),
          displayMinPurchaseQty: Number(product.minPurchaseQty || 1),
          displayStepQty: Number(product.stepQty || 1),
          selectionText: "",
          selectionIncomplete: false,
          unavailable: Boolean(availability.label),
          availabilityLabel: availability.label,
          availabilityMessage: availability.message,
          confirmLabel: confirmLabels[this.properties.actionMode] || confirmLabels.cart
        });
        return;
      }

      const skus = product.skus || [];
      const initialSku = skus.find((sku) => Number(sku.id) === Number(this.properties.initialSkuId));
      const defaultSku = skus.find((sku) => sku.defaultSku && skuPurchasable(sku));
      const firstPurchasable = skus.find(skuPurchasable);
      const fallbackDefault = skus.find((sku) => sku.defaultSku);
      const activeSku = initialSku && skuPurchasable(initialSku)
        ? initialSku
        : defaultSku || firstPurchasable || fallbackDefault || skus[0];
      this.refreshSkuSelection(
        activeSku ? activeSku.optionValueIds : [],
        Number(this.properties.initialQuantity || 0),
        confirmLabels[this.properties.actionMode] || confirmLabels.cart
      );
    },

    refreshSkuSelection(selectedOptionIds, requestedQuantity, confirmLabel) {
      const product = this.properties.product || {};
      const groups = product.specGroups || [];
      const skus = product.skus || [];
      const selectedIds = (selectedOptionIds || []).map(String);
      const activeSku = skus.find((sku) => sameCombination(sku.optionValueIds, selectedIds)) || null;
      const displayGroups = groups.map((group) => ({
        ...group,
        options: (group.options || []).map((option) => {
          const otherGroupOptionIds = new Set(
            groups
              .filter((candidate) => candidate.id !== group.id)
              .flatMap((candidate) => candidate.options || [])
              .map((candidate) => String(candidate.id))
          );
          const proposedSelection = selectedIds
            .filter((id) => otherGroupOptionIds.has(String(id)))
            .concat(String(option.id));
          const compatible = skus.some((sku) => (
            skuPurchasable(sku)
            && proposedSelection.every((id) => (sku.optionValueIds || []).map(String).includes(String(id)))
          ));
          const relatedSkus = skus.filter((sku) => (
            (sku.optionValueIds || []).map(String).includes(String(option.id))
            && selectedIds
              .filter((id) => otherGroupOptionIds.has(String(id)))
              .every((id) => (sku.optionValueIds || []).map(String).includes(String(id)))
          ));
          const lowStock = relatedSkus.some((sku) => (
            Number(sku.status) === 1 && Number(sku.stockQty) > 0 && Number(sku.stockQty) <= 3
          ));
          return {
            ...option,
            selected: selectedIds.includes(String(option.id)),
            disabled: !compatible,
            lowStock
          };
        })
      }));

      const selectionIncomplete = selectedIds.length !== groups.length || !activeSku;
      const activeProduct = activeSku
        ? {
            ...product,
            stockQty: activeSku.stockQty,
            status: Number(product.status) === 1 ? activeSku.status : product.status
          }
        : product;
      const availability = selectionIncomplete
        ? {
            label: "请选择完整规格",
            message: "请先选择完整的商品规格"
          }
        : getProductAvailability(activeProduct);
      const minPurchaseQty = Number(activeSku ? activeSku.minPurchaseQty : product.minPurchaseQty || 1);
      const stockQty = Number(activeSku ? activeSku.stockQty : product.stockQty || 0);
      const quantity = Math.min(
        Math.max(Number(requestedQuantity || minPurchaseQty), minPurchaseQty),
        Math.max(stockQty, minPurchaseQty)
      );
      const unitPrice = Number(activeSku ? activeSku.unitPrice : product.minUnitPrice || product.unitPrice || 0);
      this.setData({
        skuEnabled: true,
        displayGroups,
        selectedOptionIds: selectedIds,
        activeSku,
        activeSkuId: activeSku ? Number(activeSku.id) : 0,
        quantity,
        unitPriceText: yuan(unitPrice),
        amountText: yuan(lineAmount(unitPrice, quantity)),
        displayImage: activeSku && activeSku.image ? activeSku.image : product.image,
        displaySaleUnit: activeSku ? activeSku.saleUnit : product.saleUnit || "",
        displayStockQty: stockQty,
        displayMinPurchaseQty: minPurchaseQty,
        displayStepQty: Number(activeSku ? activeSku.stepQty : product.stepQty || 1),
        selectionText: activeSku ? activeSku.specificationText : "请选择完整规格",
        selectionIncomplete,
        unavailable: Boolean(availability.label),
        availabilityLabel: availability.label,
        availabilityMessage: availability.message,
        confirmLabel: confirmLabel || this.data.confirmLabel
      });
    },

    handleSelectOption(event) {
      const { groupId, optionId, disabled } = event.currentTarget.dataset;
      if (disabled === true || disabled === "true") {
        wx.showToast({ title: "该规格组合暂不可购买", icon: "none" });
        return;
      }
      const product = this.properties.product || {};
      const group = (product.specGroups || []).find((item) => String(item.id) === String(groupId));
      if (!group) {
        return;
      }
      const groupOptionIds = new Set((group.options || []).map((option) => String(option.id)));
      const selectedOptionIds = this.data.selectedOptionIds
        .filter((id) => !groupOptionIds.has(String(id)))
        .concat(String(optionId));
      this.refreshSkuSelection(selectedOptionIds, 0, this.data.confirmLabel);
    },

    handleClose() {
      this.triggerEvent("close");
    },

    handleQuantityChange(event) {
      const quantity = event.detail.value;
      const unitPrice = this.data.activeSku
        ? this.data.activeSku.unitPrice
        : this.properties.product.unitPrice;
      this.setData({
        quantity,
        amountText: yuan(lineAmount(unitPrice, quantity))
      });
    },

    handleConfirm() {
      if (this.data.selectionIncomplete) {
        wx.showToast({ title: "请先选择完整规格", icon: "none" });
        return;
      }
      if (this.data.unavailable) {
        wx.showToast({ title: this.data.availabilityMessage, icon: "none" });
        return;
      }
      this.triggerEvent("confirm", {
        product: this.properties.product,
        sku: this.data.activeSku,
        skuId: this.data.activeSkuId || null,
        quantity: this.data.quantity,
        actionMode: this.properties.actionMode
      });
      this.handleClose();
    }
  }
});
