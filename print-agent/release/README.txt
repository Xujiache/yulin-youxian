禹邻优鲜小票打印代理

新的生产交付目录是 release\v1.2.1，内含已编译程序和 SHA256SUMS.txt。
请进入该目录，先运行 verify-checksums.ps1，再运行 setup-agent.cmd。

生产 API 强制使用 HTTPS。HTTP 仅允许 localhost/回环开发地址。
打印密钥由 Windows DPAPI 按当前用户加密，监督脚本负责异常退出重启。
