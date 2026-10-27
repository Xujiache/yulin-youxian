import { AppRouteRecordRaw } from '@/utils/router'
import { HOME_PAGE_PATH } from '../routesAlias'

export const staticRoutes: AppRouteRecordRaw[] = [
  {
    path: '/auth/login',
    name: 'Login',
    component: () => import('@views/auth/login/index.vue'),
    meta: { title: 'menus.login.title', isHideTab: true }
  },
  {
    path: '/auth/register',
    name: 'Register',
    component: () => import('@views/auth/register/index.vue'),
    meta: { title: 'menus.register.title', isHideTab: true }
  },
  {
    path: '/auth/forget-password',
    name: 'ForgetPassword',
    component: () => import('@views/auth/forget-password/index.vue'),
    meta: { title: 'menus.forgetPassword.title', isHideTab: true }
  },
  {
    path: '/403',
    name: 'Exception403',
    component: () => import('@views/exception/403/index.vue'),
    meta: { title: '403', isHideTab: true }
  },
  {
    path: '/500',
    name: 'Exception500',
    component: () => import('@views/exception/500/index.vue'),
    meta: { title: '500', isHideTab: true }
  },
  {
    path: '/outside',
    component: () => import('@views/index/index.vue'),
    name: 'Outside',
    meta: { title: 'menus.outside.title' },
    children: [
      {
        path: '/outside/iframe/:path',
        name: 'Iframe',
        component: () => import('@/views/outside/Iframe.vue'),
        meta: { title: 'iframe' }
      }
    ]
  },
  // 模板默认首页为 /dashboard，本项目已改为 /fresh/dashboard。
  // 保留重定向以兼容浏览器书签和历史记录中残留的旧地址。
  {
    path: '/dashboard',
    redirect: HOME_PAGE_PATH
  },
  {
    path: '/dashboard/:pathMatch(.*)*',
    redirect: HOME_PAGE_PATH
  },
  { path: '/fresh/categories', redirect: '/fresh/catalog/categories' },
  { path: '/fresh/products/editor', redirect: '/fresh/catalog/products/editor' },
  { path: '/fresh/products', redirect: '/fresh/catalog/products' },
  { path: '/fresh/banners', redirect: '/fresh/catalog/banners' },
  { path: '/fresh/orders', redirect: '/fresh/trade/orders' },
  { path: '/fresh/stock-overview', redirect: '/fresh/trade/stock-overview' },
  { path: '/fresh/refunds', redirect: '/fresh/trade/refunds' },
  { path: '/fresh/delivery-slots', redirect: '/fresh/trade/delivery-slots' },
  { path: '/fresh/settings', redirect: '/fresh/store/settings' },
  { path: '/fresh/printing', redirect: '/fresh/store/printing' },
  { path: '/fresh/backups', redirect: '/fresh/store/backups' },
  {
    path: '/:pathMatch(.*)*',
    name: 'Exception404',
    component: () => import('@views/exception/404/index.vue'),
    meta: { title: '404', isHideTab: true }
  }
]
