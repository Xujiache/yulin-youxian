# 生产发布检查清单

## 后端环境变量

- `SERVER_PORT`：后端监听端口。
- `ADMIN_USERNAME`：管理后台账号。
- `ADMIN_PASSWORD`：管理后台密码，禁止使用弱密码。
- `WECHAT_MINIAPP_DEVELOPMENT_MODE=false`。
- `WECHAT_MINIAPP_APP_ID`：小程序 AppID。
- `WECHAT_MINIAPP_APP_SECRET`：小程序 AppSecret。
- `WECHAT_PAY_DEVELOPMENT_MODE=false`。
- `WECHAT_PAY_APP_ID`：微信支付 AppID。
- `WECHAT_PAY_MCH_ID`：微信支付商户号。
- `WECHAT_PAY_API_V3_KEY`：微信支付 API v3 密钥。
- `WECHAT_PAY_MERCHANT_SERIAL_NO`：商户证书序列号。
- `WECHAT_PAY_PRIVATE_KEY_PATH` 或 `WECHAT_PAY_PRIVATE_KEY`：商户私钥。
- `WECHAT_PAY_PLATFORM_CERTIFICATE_PATH`：微信支付平台证书。
- `WECHAT_PAY_NOTIFY_URL`：支付回调 HTTPS 地址。
- `WECHAT_PAY_REFUND_NOTIFY_URL`：退款回调 HTTPS 地址。
- `STOREFRONT_STORAGE_PATH`：业务数据 JSON 存储路径。
- `AUTH_PROFILE_STORAGE_PATH`：用户资料 JSON 存储路径。
- `PERSISTENCE_MODE=mysql`：生产环境使用 MySQL。
- `MYSQL_URL`：MySQL JDBC 地址，必须包含 UTF-8 和 `utf8mb4` 连接配置。
- `MYSQL_USERNAME`：MySQL 应用账号。
- `MYSQL_PASSWORD`：MySQL 应用账号密码。
- `MYSQL_SHADOW_WRITE_LEGACY_JSON=true`：迁移观察期保留 JSON 影子备份。

## 小程序发布

- 在 `client-wechat/app.js` 配置正式 HTTPS 后端域名。
- 在微信公众平台配置 request/uploadFile/downloadFile 合法域名。
- 保持 `project.config.json` 的 `urlCheck=true`。
- 发布前不要开启模拟支付或开发登录模式。

## 管理后台发布

- 执行 `npm run build`。
- 将 `admin-web/dist` 部署到静态站点或 Nginx。
- 反向代理 `/api/**` 到后端服务。
- 反向代理 `/uploads/**` 到后端上传目录映射。

## 数据与文件

- `server/data` 是运行态数据目录，不应提交到代码仓库。
- 上线前确认没有测试订单、测试用户、测试退款和测试上传文件。
- 建议对 `STOREFRONT_STORAGE_PATH`、`AUTH_PROFILE_STORAGE_PATH` 和 `data/uploads` 做定期备份。
- 首次切换 MySQL 前必须按 `docs/07-mysql-migration.md` 停机备份和校验，禁止直接删除旧 JSON。
- MySQL 数据库、表和连接字符集必须全部使用 `utf8mb4`。
- 迁移后执行 `server/scripts/verify-mysql-migration.sql`，确认校验值和实体数量一致。
