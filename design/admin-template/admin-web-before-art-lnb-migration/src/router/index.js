import { createRouter, createWebHistory } from "vue-router";
import AdminLayout from "../layouts/AdminLayout.vue";
import DashboardView from "../views/DashboardView.vue";
import LoginView from "../views/LoginView.vue";
import ProductView from "../views/ProductView.vue";
import OrderView from "../views/OrderView.vue";
import RefundView from "../views/RefundView.vue";
import DeliverySlotView from "../views/DeliverySlotView.vue";
import SettingView from "../views/SettingView.vue";

const routes = [
  {
    path: "/login",
    name: "login",
    component: LoginView,
    meta: { public: true, title: "登录" }
  },
  {
    path: "/",
    component: AdminLayout,
    redirect: "/dashboard",
    children: [
      { path: "dashboard", name: "dashboard", component: DashboardView, meta: { title: "数据概览" } },
      { path: "products", name: "products", component: ProductView, meta: { title: "商品管理" } },
      { path: "orders", name: "orders", component: OrderView, meta: { title: "订单管理" } },
      { path: "refunds", name: "refunds", component: RefundView, meta: { title: "售后退款" } },
      { path: "delivery-slots", name: "delivery-slots", component: DeliverySlotView, meta: { title: "预约配送" } },
      { path: "settings", name: "settings", component: SettingView, meta: { title: "系统配置" } }
    ]
  }
];

const router = createRouter({
  history: createWebHistory(),
  routes
});

router.beforeEach((to) => {
  if (to.meta.public) {
    return true;
  }
  if (!localStorage.getItem("adminToken")) {
    return "/login";
  }
  return true;
});

export default router;
