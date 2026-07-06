# 禹邻优鲜后端服务

## 技术栈

- Java 17
- Spring Boot 4.1.0
- Maven

当前默认实现为文件持久化业务服务，已覆盖单门店小程序、后端和管理后台完整业务闭环。生产部署可以直接通过 `STOREFRONT_STORAGE_PATH` 指定持久化文件位置。

## 当前实现

- 统一响应结构：`ApiResponse`
- 小程序接口前缀：`/api/wx`
- 管理后台接口前缀：`/api/admin`
- 商品、分类、购物车、地址、预约配送、订单、微信支付、退款申请接口
- 微信小程序登录：`/api/wx/auth/login`，正式模式调用微信 `jscode2session`
- 小程序请求 token 识别：`Authorization: Bearer <token>`
- 购物车、地址、订单、退款按当前用户隔离
- 后台后端商品、订单、退款、配送、配置、统计接口统一读取同一份业务数据
- 微信支付 v3 JSAPI 下单、退款请求、支付/退款回调解密和验签入口

## 本地配置

默认读取以下环境变量：

- `SERVER_PORT`
- `STOREFRONT_STORAGE_PATH`
- `WECHAT_MINIAPP_DEVELOPMENT_MODE`
- `WECHAT_MINIAPP_APP_ID`
- `WECHAT_MINIAPP_APP_SECRET`
- `WECHAT_MINIAPP_CODE2_SESSION_URL`
- `WECHAT_PAY_DEVELOPMENT_MODE`
- `WECHAT_PAY_APP_ID`
- `WECHAT_PAY_MCH_ID`
- `WECHAT_PAY_API_V3_KEY`
- `WECHAT_PAY_MERCHANT_SERIAL_NO`
- `WECHAT_PAY_PRIVATE_KEY`
- `WECHAT_PAY_PRIVATE_KEY_PATH`
- `WECHAT_PAY_PLATFORM_CERTIFICATE_PATH`
- `WECHAT_PAY_BASE_URL`
- `WECHAT_PAY_NOTIFY_URL`
- `WECHAT_PAY_REFUND_NOTIFY_URL`

`WECHAT_MINIAPP_DEVELOPMENT_MODE` 默认为 `true`，用于本地开发时按 `clientId` 生成稳定 openId。生产部署设置为 `false` 后，后端会使用 `WECHAT_MINIAPP_APP_ID` 和 `WECHAT_MINIAPP_APP_SECRET` 调用微信 `jscode2session`。

`WECHAT_PAY_DEVELOPMENT_MODE` 默认为 `true`，支付接口会返回 development 支付参数，小程序会调用 `/api/wx/orders/{id}/pay/development-success` 完成本地流程联调。生产部署设置为 `false` 后，必须配置商户号、商户私钥、商户证书序列号、API v3 密钥、平台证书和公网 HTTPS 回调地址。

## 启动

```bash
cd server
mvn spring-boot:run
```

当前默认不依赖 MySQL/Redis，可直接启动。

## 已有接口示例

- `GET /api/ping`
- `POST /api/wx/auth/login`
- `GET /api/wx/profile`
- `GET /api/wx/categories`
- `GET /api/wx/products`
- `POST /api/wx/cart/items`
- `GET /api/wx/cart`
- `GET /api/wx/addresses`
- `GET /api/wx/delivery-slots`
- `POST /api/wx/orders/preview`
- `GET /api/wx/orders`
- `POST /api/wx/orders`
- `POST /api/wx/orders/{id}/pay`
- `POST /api/wx/orders/{id}/pay/development-success`
- `POST /api/wx/refunds`
- `POST /api/wx/payments/wechat/notify`
- `POST /api/wx/refunds/wechat/notify`
