/**
 * 公共路由别名
 * 存放系统级公共路由路径，如布局容器、登录页等
 */
export enum RoutesAlias {
  Layout = '/index/index', // 布局容器
  Login = '/auth/login' // 登录页
}

/**
 * 主页路径，默认使用菜单第一个有效路径，配置后使用此路径
 * 定义在此处而非 router/index.ts，供静态路由引用时避免循环依赖
 */
export const HOME_PAGE_PATH = '/fresh/dashboard'
