const { yuan, lineAmount } = require("../../utils/format");
const { getProduct } = require("../../api/catalog");
const { addCartItem } = require("../../api/cart");
const { requireCompleteProfile } = require("../../utils/auth-guard");

Page({
  data: {
    loading: true,
    product: null,
    quantity: 1,
    amountText: "0.00"
  },

  async onLoad(options) {
    let product;
    try {
      product = await getProduct(Number(options.id));
    } catch {
      wx.showToast({ title: "商品加载失败", icon: "none" });
      this.setData({ loading: false });
      return;
    }
    const quantity = Number(product.minPurchaseQty || 1);
    this.setData({
      product,
      quantity,
      amountText: yuan(lineAmount(product.unitPrice, quantity)),
      loading: false
    });
  },

  onShareAppMessage() {
    const product = this.data.product || {};
    const productId = product.id || "";
    return {
      title: product.name ? `禹邻优鲜｜${product.name}` : "禹邻优鲜",
      path: `/pages/product-detail/index?id=${productId}`,
      imageUrl: product.image || ""
    };
  },

  onShareTimeline() {
    const product = this.data.product || {};
    const productId = product.id || "";
    return {
      title: product.name ? `禹邻优鲜｜${product.name}` : "禹邻优鲜",
      query: productId ? `id=${productId}` : "",
      imageUrl: product.image || ""
    };
  },

  handleQuantityChange(event) {
    const quantity = event.detail.value;
    this.setData({
      quantity,
      amountText: yuan(lineAmount(this.data.product.unitPrice, quantity))
    });
  },

  handleBack() {
    wx.navigateBack({
      fail() {
        wx.redirectTo({ url: "/pages/category/index" });
      }
    });
  },

  async handleAddCart() {
    if (!requireCompleteProfile()) {
      return;
    }
    try {
      await addCartItem(this.data.product.id, this.data.quantity);
      wx.showToast({ title: "已加入购物车", icon: "success" });
    } catch {
      wx.showToast({ title: "加入失败，请重试", icon: "none" });
    }
  },

  async handleBuyNow() {
    if (!requireCompleteProfile()) {
      return;
    }
    try {
      await addCartItem(this.data.product.id, this.data.quantity);
      wx.navigateTo({ url: "/pages/checkout/index" });
    } catch {
      wx.showToast({ title: "购买失败，请重试", icon: "none" });
    }
  }
});
