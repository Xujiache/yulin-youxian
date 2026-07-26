# 真实微信支付上线配置

本项目后端已经使用微信支付 API v3 的小程序/JSAPI 支付接口。生产环境必须关闭 development 模式，并配置真实小程序、商户号、商户 API 私钥、API v3 密钥、平台证书和公网 HTTPS 回调地址。

## 必需配置

```text
WECHAT_MINIAPP_DEVELOPMENT_MODE=false
WECHAT_MINIAPP_APP_ID=小程序 AppID
WECHAT_MINIAPP_APP_SECRET=小程序 AppSecret

WECHAT_PAY_DEVELOPMENT_MODE=false
WECHAT_PAY_APP_ID=与小程序绑定的 AppID
WECHAT_PAY_MCH_ID=微信支付商户号
WECHAT_PAY_API_V3_KEY=32 位 APIv3 密钥
WECHAT_PAY_MERCHANT_SERIAL_NO=商户 API 证书序列号
WECHAT_PAY_PRIVATE_KEY_PATH=/安全目录/apiclient_key.pem
WECHAT_PAY_PLATFORM_CERTIFICATE_PATH=/安全目录/wechatpay平台证书.pem
WECHAT_PAY_NOTIFY_URL=https://你的域名/api/wx/payments/wechat/notify
WECHAT_PAY_REFUND_NOTIFY_URL=https://你的域名/api/wx/refunds/wechat/notify
```

`WECHAT_PAY_PRIVATE_KEY` 也支持直接传入 PKCS8 PEM，但生产环境建议使用文件路径，避免把私钥放进环境变量、日志或源码。

## 微信平台侧准备

1. 认证小程序，并在微信支付商户平台绑定 AppID。
2. 开通 JSAPI/小程序支付权限。
3. 创建并下载商户 API 证书，取得商户 API 证书序列号和 `apiclient_key.pem`。
4. 设置 APIv3 密钥，必须是 32 个字符。
5. 下载与当前商户号对应的平台证书。
6. 为两个回调地址配置公网 HTTPS，不能使用 localhost、内网 IP 或带查询参数的地址。
7. 在微信公众平台配置小程序合法 request 域名，至少包含后端 HTTPS 域名。

## 上线前验证

- 后端启动日志和 `/api/wx/payments/wechat/config-check` 均确认 `developmentMode=false`。
- 使用真实微信用户登录，不能使用 `dev_openid`。
- 先使用 0.01 元真实订单验证下单、支付回调和订单状态。
- 验证用户取消支付时订单仍为待支付，重复回调不会重复处理。
- 发起一笔小额退款，验证退款回调后退款状态更新。
- 不要把 AppSecret、APIv3 密钥、商户私钥或平台证书提交到 Git。
