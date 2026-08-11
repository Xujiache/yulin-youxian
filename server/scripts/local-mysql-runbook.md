# 本机 MySQL 8 + Flyway 迁移 运行手册（Windows / PowerShell）

本文档记录的是 **实际执行并验证通过** 的命令序列（2026-08-11，Windows 10，无 Docker、无 winget、无 choco）。
用途：本地开发库与迁移演练。生产环境请改用运维已有的 MySQL 实例，只需执行「三、建库建账号」和「五、启动」。

---

## 一、下载 MySQL 8（免安装 zip）

`dev.mysql.com` 与 `cdn.mysql.com` 在本网络下分别返回 403 / 404，清华与中科大镜像无 MySQL 目录。
实测可用的是阿里云镜像（最高版本为 8.0.28）：

```powershell
New-Item -ItemType Directory -Force -Path "C:\mysql-dl"
curl.exe -sSL --max-time 3000 -o "C:\mysql-dl\mysql-8.0.28-winx64.zip" `
  "https://mirrors.aliyun.com/mysql/MySQL-8.0/mysql-8.0.28-winx64.zip"
# 211.7 MB，SHA256 = CB207AFB33E2BBFE9535BA78FA9A1A3CCCD11156F73864A5DB0BE8DCC2DBB1BC
```

## 二、解压、写配置、初始化、注册服务

```powershell
Expand-Archive -Path "C:\mysql-dl\mysql-8.0.28-winx64.zip" -DestinationPath "C:\mysql-extract" -Force
Move-Item "C:\mysql-extract\mysql-8.0.28-winx64" "C:\mysql8"
```

`C:\mysql8\my.ini`（**不要加 `skip-name-resolve`**：加了以后 TCP 连 127.0.0.1 不会匹配 `root@localhost`，
会报 `ERROR 1130 Host '127.0.0.1' is not allowed to connect`）：

```ini
[mysqld]
basedir=C:/mysql8
datadir=C:/mysql8/data
port=3306
bind-address=127.0.0.1
character-set-server=utf8mb4
collation-server=utf8mb4_unicode_ci
default-time-zone='+08:00'
default-authentication-plugin=mysql_native_password
max_connections=300
log-error=C:/mysql8/data/mysql-error.log

[client]
port=3306
default-character-set=utf8mb4

[mysql]
default-character-set=utf8mb4
```

```powershell
& "C:\mysql8\bin\mysqld.exe" --defaults-file="C:\mysql8\my.ini" --initialize-insecure --console
& "C:\mysql8\bin\mysqld.exe" --install MySQL80 --defaults-file="C:\mysql8\my.ini"
net start MySQL80
```

## 三、建库建账号（与生产一致）

```sql
CREATE DATABASE IF NOT EXISTS yulin_fresh CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE USER IF NOT EXISTS 'yulin_fresh'@'127.0.0.1' IDENTIFIED BY 'Yulin@2026';
CREATE USER IF NOT EXISTS 'yulin_fresh'@'localhost' IDENTIFIED BY 'Yulin@2026';

GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES, DROP
    ON yulin_fresh.* TO 'yulin_fresh'@'127.0.0.1';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES, DROP
    ON yulin_fresh.* TO 'yulin_fresh'@'localhost';

FLUSH PRIVILEGES;
ALTER USER 'root'@'localhost' IDENTIFIED BY 'Yulin@2026';
FLUSH PRIVILEGES;
```

执行（**用 `cmd /c` 重定向，PowerShell 的 `>` 会写成 UTF-16 导致中文错乱**）：

```powershell
$env:Path = "C:\mysql8\bin;$env:Path"
cmd /c "mysql.exe -h 127.0.0.1 -P 3306 -u root --default-character-set=utf8mb4 < setup.sql"
```

## 四、打包

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
cd server
.\mvnw.cmd package -DskipTests "-Dbuild.dir=target-mysql"
```

## 五、启动（Flyway 自动执行 V1–V11）

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:MYSQL_URL = "jdbc:mysql://127.0.0.1:3306/yulin_fresh?useUnicode=true&characterEncoding=UTF-8&connectionCollation=utf8mb4_unicode_ci&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"
$env:MYSQL_USERNAME = "yulin_fresh"
$env:MYSQL_PASSWORD = "Yulin@2026"
$env:SERVER_PORT = "8081"

$p = Start-Process -FilePath "$env:JAVA_HOME\bin\java.exe" `
  -ArgumentList '-Dfile.encoding=UTF-8','-jar','target-mysql\fresh-delivery-server-0.0.1-SNAPSHOT.jar' `
  -RedirectStandardOutput "server-mysql-8081.log" -RedirectStandardError "server-mysql-8081.err.log" `
  -PassThru -NoNewWindow
$p.Id | Out-File -Encoding ascii "server-mysql-8081.pid"
```

停止：

```powershell
Stop-Process -Id (Get-Content server\server-mysql-8081.pid) -Force
```

> PowerShell 5.1 传参给原生 exe 时会吞掉引号。`java -Dfile.encoding=UTF-8` 必须写成 `java "-Dfile.encoding=UTF-8"`；
> `curl.exe -d '{"a":"b"}'` 必须改成 `--data-binary "@body.json"`，否则服务端收到的是非法 JSON（表现为 400）。

## 六、验收

```powershell
cmd /c "mysql.exe -h 127.0.0.1 -P 3306 -u yulin_fresh --default-character-set=utf8mb4 -t yulin_fresh < scripts\verify-mysql-migration.sql"
```

预期：`flyway_schema_history` 11 条且 `success=1`；`information_schema` 中 `yulin_fresh` 共 37 张表
（36 业务表 + `flyway_schema_history`）；`SELECT COUNT(*) FROM delivery_config` = 77
（V8 的 72 条 + V9 的 2 条高德 Key + V11 的 3 条完整性配置）。
V10 额外新增 7 张 `marketing_lottery_*` 表，并给 `delivery_task` 增加随机减免和赠品摘要字段。
V11 新增 `auth_session`，并加固配送事件幂等、结算明细唯一性、异常查询索引与高德服务端配置。
