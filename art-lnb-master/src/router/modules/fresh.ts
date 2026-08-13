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
      path: 'products/editor',
      name: 'FreshProductEditor',
      component: '/fresh/products/editor',
      meta: {
        title: '编辑商品',
        isHide: true,
        keepAlive: false
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
    },
    {
      path: 'printing',
      name: 'FreshPrinting',
      component: '/fresh/printing',
      meta: {
        title: '小票打印',
        icon: 'ri:printer-line',
        keepAlive: true
      }
    },
    {
      path: 'backups',
      name: 'FreshBackups',
      component: '/fresh/backups',
      meta: {
        title: '数据备份',
        icon: 'ri:database-2-line',
        keepAlive: false
      }
    },
    {
      path: 'delivery/board',
      name: 'FreshDeliveryBoard',
      component: '/fresh/delivery/board/index',
      meta: {
        title: '配送调度台',
        icon: 'ri:navigation-line',
        keepAlive: true,
        fixedTab: true
      }
    },
    {
      path: 'delivery/tasks',
      name: 'FreshDeliveryTasks',
      component: '/fresh/delivery/tasks/index',
      meta: {
        title: '配送任务',
        icon: 'ri:task-line',
        keepAlive: true
      }
    },
    {
      path: 'delivery/waves',
      name: 'FreshDeliveryWaves',
      component: '/fresh/delivery/waves/index',
      meta: {
        title: '配送波次',
        icon: 'ri:stack-line',
        keepAlive: true
      }
    },
    {
      path: 'delivery/waves/:id',
      name: 'FreshDeliveryWaveDetail',
      component: '/fresh/delivery/waves/detail',
      meta: {
        title: '波次详情',
        isHide: true
      }
    },
    {
      path: 'delivery/riders',
      name: 'FreshDeliveryRiders',
      component: '/fresh/delivery/riders/index',
      meta: {
        title: '骑手管理',
        icon: 'ri:team-line',
        keepAlive: true
      }
    },
    {
      path: 'delivery/riders/:id',
      name: 'FreshDeliveryRiderDetail',
      component: '/fresh/delivery/riders/detail',
      meta: {
        title: '骑手详情',
        isHide: true
      }
    },
    {
      path: 'delivery/exceptions',
      name: 'FreshDeliveryExceptions',
      component: '/fresh/delivery/exceptions/index',
      meta: {
        title: '异常处理',
        icon: 'ri:error-warning-line',
        keepAlive: true
      }
    },
    {
      path: 'delivery/settlements',
      name: 'FreshDeliverySettlements',
      component: '/fresh/delivery/settlements/index',
      meta: {
        title: '骑手结算',
        icon: 'ri:wallet-3-line',
        keepAlive: true
      }
    },
    {
      path: 'delivery/analytics',
      name: 'FreshDeliveryAnalytics',
      component: '/fresh/delivery/analytics/index',
      meta: {
        title: '配送分析',
        icon: 'ri:bar-chart-2-line',
        keepAlive: true
      }
    },
    {
      path: 'delivery/settings',
      name: 'FreshDeliverySettings',
      component: '/fresh/delivery/settings/index',
      meta: {
        title: '调度参数',
        icon: 'ri:sliders-line',
        keepAlive: true
      }
    },
    {
      path: 'marketing',
      name: 'FreshMarketing',
      component: '',
      meta: {
        title: '营销中心',
        icon: 'ri:megaphone-line'
      },
      children: [
        {
          path: 'lucky-draw',
          name: 'FreshLuckyDraw',
          component: '/fresh/marketing/lucky-draw/index',
          meta: {
            title: '随机减免',
            icon: 'ri:coupon-3-line',
            keepAlive: false
          }
        },
        {
          path: 'lucky-draw/records',
          name: 'FreshLuckyDrawRecords',
          component: '/fresh/marketing/lucky-draw/records',
          meta: {
            title: '中奖记录',
            icon: 'ri:gift-line',
            keepAlive: true
          }
        }
      ]
    }
  ]
}
