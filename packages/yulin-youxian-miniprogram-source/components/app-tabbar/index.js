const routeMap = {
  home: "/pages/home/index",
  category: "/pages/category/index",
  cart: "/pages/cart/index",
  orders: "/pages/orders/index",
  profile: "/pages/profile/index"
};

const protectedTabs = ["cart", "orders", "profile"];

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
    tabs: [
      { key: "home", text: "首页", icon: "/assets/icons/tab-home.svg", activeIcon: "/assets/icons/tab-home-active.svg" },
      { key: "category", text: "分类", icon: "/assets/icons/tab-category.svg", activeIcon: "/assets/icons/tab-category-active.svg" },
      { key: "cart", text: "购物车", icon: "/assets/icons/tab-cart.svg", activeIcon: "/assets/icons/tab-cart-active.svg" },
      { key: "orders", text: "订单", icon: "/assets/icons/tab-orders.svg", activeIcon: "/assets/icons/tab-orders-active.svg" },
      { key: "profile", text: "我的", icon: "/assets/icons/tab-profile.svg", activeIcon: "/assets/icons/tab-profile-active.svg" }
    ]
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
