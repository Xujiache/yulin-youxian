# 禹邻优鲜微信小程序客户端

## 当前范围

已完成原生微信小程序客户端页面和后端接口对接：

- 首页
- 分类商品页
- 商品详情页
- 加购弹窗
- 购物车页
- 确认订单页
- 订单列表页
- 订单详情页
- 退款申请页
- 我的页面
- 地址管理页

## 打开方式

1. 打开微信开发者工具。
2. 选择「导入项目」。
3. 项目目录选择：`C:/Users/Administrator/Desktop/生鲜同城/client-wechat`
4. 本地开发可使用测试号；生产请使用客户正式小程序 AppID。

## 实现说明

- 当前数据来自后端 `/api/wx` 接口。
- 接口封装已放在 `api` 和 `utils/request.js`。
- 小程序启动时会调用 `/api/wx/auth/login`，后续请求自动携带 `Authorization: Bearer <token>`。
- 我的页面会调用 `/api/wx/profile` 读取用户资料和订单统计。
- 当前图片为 `assets/products` 下的默认商品图，后续也可通过后台商品图片字段替换。
- 商品数量选择已支持每个商品独立配置最小购买量和步进值。
- 支付页已接入后端返回的微信支付参数；后端 development 模式下会自动走本地支付确认接口，正式模式下调用 `wx.requestPayment`。
- 登录页启动流程已调用 `wx.login`，后端正式模式会通过微信 `jscode2session` 换取真实 openId。

## 生产配置

1. 把 `app.js` 里的 `apiBaseUrl` 改为线上 HTTPS 域名。
2. 后端设置 `WECHAT_MINIAPP_DEVELOPMENT_MODE=false`，并配置正式小程序 AppID 和 AppSecret。
3. 后端设置 `WECHAT_PAY_DEVELOPMENT_MODE=false`，并配置微信支付商户证书、API v3 密钥和公网 HTTPS 回调地址。
4. 使用客户正式小程序 AppID 提交体验版并完成支付、退款、回调验签联调。
