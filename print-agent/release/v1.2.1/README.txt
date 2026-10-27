禹邻优鲜小票打印代理 1.2.1

1. 先运行：
   powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\verify-checksums.ps1
2. 校验通过后双击 setup-agent.cmd。
3. 正式门店只允许填写 https://正式域名/api；HTTP 仅允许 localhost/回环开发地址。
4. 配置成功后双击 install-autostart.cmd。

打印密钥由 Windows DPAPI 按当前用户加密，必须始终使用配置时的同一
Windows 用户。supervise-agent.ps1 会监督并重启异常退出的代理，日志自动轮转。

详细说明见 使用说明.md。
