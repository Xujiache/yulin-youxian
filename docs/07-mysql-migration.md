# MySQL 无损迁移手册

## 目标

- MySQL 8.0 成为生产数据主存储。
- 原有 `storefront-state.json` 和 `user-profiles.json` 首次启动自动导入。
- 导入前自动生成 `.pre-mysql.bak` 不可变备份。
- 所有文本使用 `utf8mb4`，金额和数量保持原始 JSON 数值。
- 每次写入保存 SHA-256，读取时自动校验。
- 迁移观察期继续写入原 JSON，支持快速回滚。

## 迁移前检查

1. 确认实际运行目录和数据路径：

```bash
pm2 describe yulin-youxian-server
printenv STOREFRONT_STORAGE_PATH
printenv AUTH_PROFILE_STORAGE_PATH
```

2. 停止后端，避免备份过程中继续产生订单：

```bash
pm2 stop yulin-youxian-server
```

3. 创建带时间戳的完整备份并计算校验值：

```bash
cd /path/to/server
backup_dir="backup/pre-mysql-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$backup_dir"
cp -a data "$backup_dir/data"
find "$backup_dir/data" -type f -print0 | sort -z | xargs -0 sha256sum > "$backup_dir/SHA256SUMS"
```

不得删除原数据目录和本次备份。

## 创建数据库

使用 MySQL 管理员账号执行：

```sql
CREATE DATABASE IF NOT EXISTS yulin_fresh
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

CREATE USER IF NOT EXISTS 'yulin_fresh'@'127.0.0.1'
    IDENTIFIED BY '替换为高强度随机密码';

GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES
    ON yulin_fresh.* TO 'yulin_fresh'@'127.0.0.1';

FLUSH PRIVILEGES;
```

## 配置后端

生产环境必须提供以下环境变量：

```bash
export PERSISTENCE_MODE=mysql
export MYSQL_URL='jdbc:mysql://127.0.0.1:3306/yulin_fresh?useUnicode=true&characterEncoding=UTF-8&connectionCollation=utf8mb4_unicode_ci&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true'
export MYSQL_USERNAME='yulin_fresh'
export MYSQL_PASSWORD='替换为数据库密码'
export MYSQL_SHADOW_WRITE_LEGACY_JSON=true
export MYSQL_REJECT_SUSPICIOUS_QUESTION_MARKS=true
export STOREFRONT_SEED_DEMO_DATA=false
```

将相同变量写入 PM2 ecosystem 配置，避免服务器重启后丢失。

## 首次启动与自动导入

部署新 JAR 后启动：

```bash
pm2 start yulin-youxian-server
pm2 logs yulin-youxian-server --lines 200
```

首次启动流程：

1. Flyway 创建 `application_state`。
2. 数据库中没有 `storefront` 时读取原业务 JSON。
3. 严格校验 UTF-8 和 JSON 格式。
4. 检测 Unicode 替换字符和连续问号，发现疑似历史乱码时拒绝导入。
5. 创建 `storefront-state.json.pre-mysql.bak`。
6. 导入 MySQL并比对 SHA-256。
7. 用户资料以相同方式导入为 `user-profiles`。

日志中应出现 `Imported legacy state storefront into MySQL` 和 `Imported legacy state user-profiles into MySQL`。

## 数据验证

执行：

```bash
mysql --default-character-set=utf8mb4 \
  -h 127.0.0.1 -u yulin_fresh -p yulin_fresh \
  < server/scripts/verify-mysql-migration.sql
```

验收条件：

- `storefront` 和 `user-profiles` 均存在。
- 两行 `checksum_ok` 均为 `1`。
- 数据库及连接字符集均为 `utf8mb4`。
- 商品、订单、退款、地址、分类、轮播图和用户数量与迁移前一致。
- 管理后台随机打开中文商品、中文地址和历史订单，内容无乱码。
- 小程序登录、购物车、下单、订单详情和退款记录正常。

## 回滚

发现问题时：

```bash
pm2 stop yulin-youxian-server
export PERSISTENCE_MODE=file
pm2 start yulin-youxian-server
```

迁移观察期默认开启影子写入，因此当前 JSON 包含迁移后的最新写入。如果需要回到迁移时刻，将完整备份目录中的 `data` 恢复到原路径后再启动旧版本。

回滚前后都不得删除 MySQL 数据、原 JSON、`.pre-mysql.bak` 或完整备份目录。
