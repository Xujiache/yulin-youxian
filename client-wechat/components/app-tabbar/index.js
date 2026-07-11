const routeMap = {
  home: "/pages/home/index",
  category: "/pages/category/index",
  cart: "/pages/cart/index",
  orders: "/pages/orders/index",
  profile: "/pages/profile/index"
};

const protectedTabs = ["cart", "orders", "profile"];
const { isGlassModeEnabled } = require("../../utils/theme");

Component({
  properties: {
    active: {
      type: String,
      value: "home"
    },
    cartCount: {
      type: Number,
      value: 0
    }
  },

  data: {
    glassMode: false,
    tabs: [
      { key: "home", text: "首页", icon: "/assets/icons/tab-home.png", activeIcon: "/assets/icons/tab-home-active.png" },
      { key: "category", text: "分类", icon: "/assets/icons/tab-category.png", activeIcon: "/assets/icons/tab-category-active.png" },
      { key: "cart", text: "购物车", icon: "/assets/icons/tab-cart.png", activeIcon: "/assets/icons/tab-cart-active.png" },
      { key: "orders", text: "订单", icon: "/assets/icons/tab-orders.png", activeIcon: "/assets/icons/tab-orders-active.png" },
      { key: "profile", text: "我的", icon: "/assets/icons/tab-profile.png", activeIcon: "/assets/icons/tab-profile-active.png" }
    ]
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

  methods: {
    handleTap(event) {
      const key = event.currentTarget.dataset.key;
      const url = routeMap[key];
      if (!url || key === this.properties.active) {
        return;
      }
      const app = getApp();
      if (protectedTabs.includes(key) && (!app.isLoggedIn || !app.isLoggedIn())) {
        wx.navigateTo({
          url: `/pages/login/index?redirect=${encodeURIComponent(url)}`
        });
        return;
      }
      wx.redirectTo({ url });
    }
  }
});
