# 发布清单

## 构建与产物

- [ ] 发布提交已合并，工作区干净，记录提交 SHA。
- [ ] 使用 `deploy/versions.env` 中的 Java、Node、npm 版本。
- [ ] 后台仅执行 `npm ci`，未改写 `package-lock.json`。
- [ ] GitHub CI 的后端、后台、Android debug、小程序、打印代理和 secret scan 全绿。
- [ ] 有 Android 签名 secrets 时，signed release gate 与 `apksigner verify` 已通过；无 secrets 时不交付 release APK。
- [ ] `build-release.sh` 产生新版本目录，旧版本目录、旧 jar 和旧 dist 未被覆盖。
- [ ] 发布目录内 `SHA256SUMS` 校验通过，传输用 tarball 的 `.sha256` 也通过。
- [ ] 打印代理交付包的 `SHA256SUMS.txt` 已由另一台 Windows 机器验证。

## 上线前演练

- [ ] `/etc/yulin-youxian/server.env` 权限为 `600`，`TZ=Asia/Shanghai`。
- [ ] `ADMIN_PASSWORD` 已设置且不是默认密码；A1 的生产启动检查已确认生效。
- [ ] 正式域名和打印代理 API 均为 HTTPS，证书有效。
- [ ] `go-live.sh --preflight` 已通过。
- [ ] `rollback.sh --check` 已确认 current 与 N-1 都存在且 manifest 正确。
- [ ] 最近一次 MySQL 备份已执行 `restore-drill.sh`，在隔离库验证 Flyway V11 与 36 张业务表。
- [ ] 数据目录和日志目录为绝对路径，磁盘余量超过阈值。

## 上线

- [ ] go-live 自动停旧进程后生成了非空 `pre-deploy-*.sql.gz` 与 SHA256。
- [ ] `current` 原子切换到新版本，`previous` 指向 N-1。
- [ ] PM2 使用单实例 ecosystem 执行 `startOrReload --update-env`，随后执行 `pm2 save`。
- [ ] Nginx `nginx -t` 通过并 reload。
- [ ] readiness 同时通过 manifest、Flyway V11、36 张业务表、存储写入、磁盘、备份年龄和 Actuator。
- [ ] SSE 响应头含 `X-Accel-Buffering: no`，调度台持续连接至少 10 分钟无批量延迟。
- [ ] 管理后台、骑手 debug/release 策略、小程序关键路径和门店测试打印已验收。

## 回滚与观察

- [ ] 发布窗口内未删除 N-1 目录。
- [ ] 已记录新旧版本号、manifest SHA、备份文件名和操作者。
- [ ] 发生 readiness/业务故障时执行 `rollback.sh`，不手工覆盖 jar/dist，不回退 Flyway 表。
- [ ] 只有数据损坏且负责人批准时才执行隔离验证过的恢复流程。
- [ ] 上线后观察 PM2、Nginx、备份年龄、磁盘和打印代理监督日志。
