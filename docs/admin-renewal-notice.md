# 管理后台续费提醒

本功能仅为提醒，不代表自动判定账号、域名或 DNS 套餐的到期日，不停用账号、不拦截业务 API，也不影响小程序。

## 显示行为

- 所有已登录管理员统一显示红色常驻报错提示：`账号服务即将到期，请联系管理员续费。`
- 不包含金额、日期或支付信息；无关闭按钮，不自动消失，点击和 Esc 不会关闭。
- 提示位于后台顶部导航栏的空位中，在面包屑右侧、搜索框左侧；参与正常布局，不再悬浮在左侧菜单上。窄屏允许文字换行，不遮挡工具栏操作。
- 登录、刷新和切换后台页面均保持生效；退出登录后移除。
- 在后台公共导航栏中挂载单实例组件，不以登录失败或伪造接口错误实现提醒。登录页及无后台导航栏的独立异常页不挂载提醒。

## 运营开关

部署根目录默认 `/opt/yulin-youxian`。开关保存在跨版本持久目录 `runtime/admin-renewal.json`，只包含 `enabled` 布尔值，不包含账号或任何秘密。

由有服务器权限的运维人员执行：

```bash
# 开启
bash /opt/yulin-youxian/current/ops/scripts/set-admin-renewal.sh on
# 续费后关闭
bash /opt/yulin-youxian/current/ops/scripts/set-admin-renewal.sh off
```

脚本原子写入配置并保留变更前备份；无需重启后端或重新构建。浏览器每 30 秒读取一次，重新聚焦页面也会立即更新。尚未加载本版本的旧页面需要刷新一次。

HTTP `/admin-renewal.json` 只读且禁止缓存，不提供浏览器端修改开关的入口。新环境需先运行脚本初始化文件，再使用更新后的 Nginx 模板。未读取到合法配置时不虚构到期状态；暂时读取失败不会移除已显示的提醒，5 秒请求超时后继续定期重试。

## 验证

```bash
cd art-lnb-master
npm run test:admin-renewal
npx --no-install eslint src/App.vue src/hooks/core/useAdminRenewalNotice.ts src/components/business/admin-renewal-notice/index.vue src/components/core/layouts/art-header-bar/index.vue scripts/admin-renewal.test.mjs
# 构建后再检查类型：构建会生成模板项目的自动导入声明。
npm run build
bash -n ../deploy/scripts/set-admin-renewal.sh
```

浏览器验收：未登录不显示；真实登录后显示；等待、Esc、点击及切页不关闭；刷新仍显示；桌面导航栏中提示在面包屑与搜索之间，窄屏不溢出工具栏、不遮挡按钮；运维关闭后当前会话自动移除，重新开启后恢复；退出登录后移除。网络失败、非法 JSON 和慢请求应保留最后一次成功读取的状态，且不会产生重复提示。

## 发布与回滚

前端发布需要同时包含组件、全局挂载和 Nginx 精确匹配配置；保留现有其他 Nginx 路由。无需数据库迁移或修改管理员凭据。回滚前端版本前，先用脚本关闭提醒；需要完整回滚时恢复发布前的 Nginx 备份并执行 `nginx -t`，通过后再 reload。禁止以清空其他配置或重启全部进程的方式操作。
