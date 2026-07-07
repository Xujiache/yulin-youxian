import { AppRouteRecord } from '@/types/router'

export const freshRoutes: AppRouteRecord = {
  path: '/fresh',
  name: 'Fresh',
  component: '/index/index',
  meta: {
    title: '禹邻优鲜',
    icon: 'ri:store-2-line',
    roles: ['R_SUPER', 'R_ADMIN']
  },
  children: [
    {
      path: 'dashboard',
      name: 'FreshDashboard',
      component: '/fresh/dashboard',
      meta: {
        title: '数据概览',
        icon: 'ri:dashboard-3-line',
        keepAlive: false,
        fixedTab: true
      }
    },
    {
      path: 'categories',
      name: 'FreshCategories',
      component: '/fresh/categories',
      meta: {
        title: '分类管理',
        icon: 'ri:folder-2-line',
        keepAlive: true
      }
    },
    {
      path: 'products',
      name: 'FreshProducts',
      component: '/fresh/products',
      meta: {
        title: '商品管理',
        icon: 'ri:shopping-basket-2-line',
        keepAlive: true
      }
    },
    {
      path: 'banners',
      name: 'FreshBanners',
      component: '/fresh/banners',
      meta: {
        title: '首页轮播图',
        icon: 'ri:image-line',
        keepAlive: true
      }
    },
    {
      path: 'orders',
      name: 'FreshOrders',
      component: '/fresh/orders',
      meta: {
        title: '订单管理',
        icon: 'ri:file-list-3-line',
        keepAlive: true
      }
    },
    {
      path: 'stock-overview',
      name: 'FreshStockOverview',
      component: '/fresh/stock-overview',
      meta: {
        title: '备货总览',
        icon: 'ri:archive-stack-line',
        keepAlive: true
      }
    },
    {
      path: 'refunds',
      name: 'FreshRefunds',
      component: '/fresh/refunds',
      meta: {
        title: '售后退款',
        icon: 'ri:refund-2-line',
        keepAlive: true
      }
    },
    {
      path: 'delivery-slots',
      name: 'FreshDeliverySlots',
      component: '/fresh/delivery-slots',
      meta: {
        title: '预约配送',
        icon: 'ri:truck-line',
        keepAlive: true
      }
    },
    {
      path: 'settings',
      name: 'FreshSettings',
      component: '/fresh/settings',
      meta: {
        title: '门店设置',
        icon: 'ri:settings-3-line',
        keepAlive: true
      }
    }
  ]
}
