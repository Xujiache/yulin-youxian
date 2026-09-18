# 2026-09-09 磁盘清理记录

清理范围仅限禹邻优鲜及其历史部署产物。根分区清理前约 69 MiB 可用，清理后约 1.4 GiB 可用（仍约 99% 占用），净释放约 1.3 GiB。

## 已处理

- 删除 13 个较早的服务端编译 JAR、一个原始未打包 JAR、生成的 Surefire XML 测试报告，合计 429,643,579 字节。详细目标列表在本机 `tmp/cleanup-20260909.cjs`。保留文本测试摘要。
- 删除 `/tmp/yulin-item-refund-build.f9TLzZ` 临时构建目录，约 48 MiB。删除前验证其中最终 JAR 的 SHA-256 与当前线上 JAR 完全相同。
- 删除 `art-lnb-master/node_modules` 和本地 `art-lnb-master/dist`，约 478 MiB。二者均为 Git 忽略的可重建内容；线上实际使用的 `/var/www/yulin-youxian/admin-current` 及各发布目录未删除。
- 将约 403 MiB 的 PM2 标准输出日志轮转压缩为约 13 MiB 的 `yulin-youxian-server-out.log.1.gz`，`gzip -t` 校验通过。使用 copytruncate，不重启服务；轮转瞬间极少量并发日志存在该方式固有的丢失风险。
- 新增主机 `/etc/logrotate.d/yulin-youxian`：每天轮转，超过 50 MiB 可提前轮转，保留 7 个压缩历史文件。
- 新增主机 `/etc/cron.d/yulin-youxian-logrotate`：每小时第 17 分钟检查此项目日志，共用系统 logrotate 状态文件；cron 服务已确认运行。

## 明确保留

- 全部源码、Git 仓库与未提交的新商品退款功能代码、设计图和轮播图。
- 数据库、上传图片、退款和配送凭证、5 份自动业务备份；没有执行任何数据恢复。
- 当前线上 JAR（SHA-256 `81e7ca9525d74d4cde3711cbe6cdf789ce3a9b8f83989c01e9b5b5d73648873e`）及 9 月 6 日发布前的回滚包，也保留了近期的历史部署 JAR。
- 其他项目、Docker 容器与卷、全局包管理缓存均未清理。

## 恢复与后续

删除的旧二进制文件没有放入回收站，原字节不可直接撤销恢复；可用对应源码重建，但不同构建环境不保证二进制逐字节相同。旧部署目录中的数据库/文件快照仍保留。

下次构建后台前，在 `art-lnb-master` 执行 `npm ci` 恢复依赖，再运行 `npm run build`。压缩日志可通过 `zless /root/.pm2/logs/yulin-youxian-server-out.log.1.gz` 查看。

清理后验证：本地 `/actuator/health` 为 `UP`，公网 `https://hqhjxt.vip/` 返回 HTTP 200；线上 JAR 校验值未改变，原有 Git 改动保留。仍需另行确认是否扩大到服务器其他项目、容器缓存等范围，或扩容磁盘，才能获得更充足的容量。
