using System;
using System.Collections;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Net;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Web.Script.Serialization;

internal static class Program
{
    private const string Version = "1.2.1";
    private static readonly JavaScriptSerializer Json = new JavaScriptSerializer { MaxJsonLength = Int32.MaxValue };
    private static readonly string BaseDirectory = AppDomain.CurrentDomain.BaseDirectory;
    private static readonly string ConfigPath = Path.Combine(BaseDirectory, "agent.config.json");
    private static readonly byte[] KeyEntropy = Encoding.UTF8.GetBytes("YulinYouxian.PrintAgent.AccessKey.v1");

    private static int Main(string[] args)
    {
        try
        {
            string command = args != null && args.Length > 0 ? args[0].Trim().ToLowerInvariant() : "run";
            if (command == "setup")
            {
                Setup();
                return 0;
            }
            if (command == "scan")
            {
                ScanUsb();
                return 0;
            }
            AgentConfig config = LoadConfig();
            if (command == "self-test")
            {
                using (XprinterClient printer = new XprinterClient(config))
                {
                    printer.Connect();
                    printer.Print(BuildSelfTestReceipt());
                }
                Console.WriteLine("测试小票已发送到打印机。");
                return 0;
            }
            Run(config);
            return 0;
        }
        catch (Exception exception)
        {
            AgentLog.Write("启动失败: " + exception.Message);
            Console.WriteLine();
            Console.WriteLine("========================================");
            Console.WriteLine("出错了：" + exception.Message);
            Console.WriteLine("========================================");
            Console.WriteLine("请把上面这行红字截图发给管理员。");
            WaitForKey();
            return 1;
        }
    }

    private static void WaitForKey()
    {
        try
        {
            if (Console.IsInputRedirected)
            {
                return;
            }
            Console.WriteLine("按回车键关闭窗口……");
            Console.ReadLine();
        }
        catch
        {
        }
    }

    private static void Setup()
    {
        Console.WriteLine("========================================");
        Console.WriteLine(" 禹邻优鲜 小票打印代理  配置向导 " + Version);
        Console.WriteLine("========================================");
        Console.WriteLine();
        AgentConfig previous = File.Exists(ConfigPath) ? TryLoadConfig() : new AgentConfig();
        AgentConfig config = new AgentConfig();

        // step 1: API URL
        Console.WriteLine("第 1 步：填写后端 API 地址");
        Console.WriteLine("（本地开发仅允许：http://localhost:8080/api）");
        Console.WriteLine("（生产环境强制：https://你的域名/api，注意末尾必须带 /api）");
        string defaultApiUrl = ValueOr(previous.ApiBaseUrl, "http://localhost:8080/api");
        config.ApiBaseUrl = ReadRequired("API 地址", defaultApiUrl).TrimEnd('/');
        Console.WriteLine();

        // step 2: access key
        Console.WriteLine("第 2 步：填写打印密钥");
        Console.WriteLine("（在管理后台小票打印页面点击生成代理密钥，复制粘贴到这里）");
        config.AccessKey = ReadSecretOrExisting("打印密钥", previous.AccessKey);
        Console.WriteLine();

        // step 3: printer connection
        Console.WriteLine("第 3 步：连接打印机（正在自动查找 USB 打印机…）");
        DetectConnection(config, previous);
        Console.WriteLine();

        config.CutPaper = true;
        config.PollSeconds = Math.Max(2, previous.PollSeconds <= 0 ? 3 : previous.PollSeconds);

        ValidateConfig(config);
        SaveConfig(config);
        Console.WriteLine("配置已加密保存到 " + ConfigPath);
        Console.WriteLine();

        // step 4: local test print (hardware verification)
        Console.WriteLine("第 4 步：打印测试小票（确认打印机正常）");
        try
        {
            using (XprinterClient printer = new XprinterClient(config))
            {
                printer.Connect();
                printer.Print(BuildSelfTestReceipt());
            }
            Console.WriteLine();
            Console.WriteLine(">>> 测试小票已发出！请查看打印机是否吐出一张小票。 <<<");
        }
        catch (Exception exception)
        {
            Console.WriteLine();
            Console.WriteLine("!!! 测试打印失败：" + exception.Message);
            Console.WriteLine("!!! 配置已保存，但打印机可能没连好。请检查电源/数据线/纸张，再重新运行本向导。");
            Console.WriteLine();
            WaitForKey();
            return;
        }
        Console.WriteLine();

        // step 5: backend connectivity test
        Console.WriteLine("第 5 步：验证后端连通性（" + config.ApiBaseUrl + "）");
        try
        {
            ApiClient.Heartbeat(config);
            Console.WriteLine(">>> 后端连接成功！代理已注册到服务器。 <<<");
            Console.WriteLine(">>> 请前往管理后台小票打印页面，确认门店打印代理显示为绿色在线。 <<<");
            Console.WriteLine(">>> 然后点击管理后台的测试打印按钮，验证整条链路是否通畅。 <<<");
        }
        catch (Exception exception)
        {
            Console.WriteLine("!!! 后端连接失败：" + exception.Message);
            Console.WriteLine("!!! 配置已保存，但无法连接后端。");
            Console.WriteLine("!!! 请检查：");
            Console.WriteLine("!!!   1. API 地址是否正确（当前：" + config.ApiBaseUrl + "）");
            Console.WriteLine("!!!   2. 打印密钥是否已关联到当前后端");
            Console.WriteLine("!!!   3. 后端服务是否已启动");
            Console.WriteLine("!!!   4. 防火墙是否放行了端口");
            Console.WriteLine("!!!");
            Console.WriteLine("!!! 可以稍后修改 agent.config.json 中的 ApiBaseUrl 后重新运行 setup。");
            Console.WriteLine();
            WaitForKey();
            return;
        }
        Console.WriteLine();

        // auto-start polling loop
        Console.WriteLine("========================================");
        Console.WriteLine(" 配置完成！正在启动自动接单打印……");
        Console.WriteLine(" 保持此窗口运行，不要关闭。");
        Console.WriteLine(" 如需开机自动启动，请双击 install-autostart.cmd。");
        Console.WriteLine("========================================");
        Console.WriteLine();
        Run(config);
    }

    private static void DetectConnection(AgentConfig config, AgentConfig previous)
    {
        List<string> devices;
        try
        {
            devices = FindUsbDevices();
        }
        catch (Exception exception)
        {
            Console.WriteLine("USB 扫描出错：" + exception.Message);
            devices = new List<string>();
        }

        if (devices.Count == 1)
        {
            config.ConnectionMode = "usb";
            config.UsbPath = devices[0];
            config.NetworkHost = "";
            config.NetworkPort = 9100;
            Console.WriteLine("已找到 USB 打印机，自动选用。");
            return;
        }

        if (devices.Count > 1)
        {
            Console.WriteLine("找到多台 USB 打印机，请输入序号选择：");
            for (int i = 0; i < devices.Count; i++)
            {
                Console.WriteLine("  " + (i + 1) + "、" + devices[i]);
            }
            int choice = ReadInt("选择第几台", 1);
            if (choice < 1 || choice > devices.Count)
            {
                choice = 1;
            }
            config.ConnectionMode = "usb";
            config.UsbPath = devices[choice - 1];
            config.NetworkHost = "";
            config.NetworkPort = 9100;
            Console.WriteLine("已选用第 " + choice + " 台 USB 打印机。");
            return;
        }

        Console.WriteLine("没有找到 USB 打印机。");
        Console.WriteLine("如果打印机是网口/WiFi 连接（有自己的 IP），请填写 IP；");
        Console.WriteLine("如果是 USB 连接却没找到，请检查电源、数据线、并安装芯烨 58 系列驱动，然后重新运行本向导。");
        string ip = ReadValue("打印机 IP（USB 打印机可直接留空回车重试）", ValueOr(previous.NetworkHost, ""));
        if (String.IsNullOrWhiteSpace(ip))
        {
            throw new InvalidOperationException("没有找到 USB 打印机，也没有填写网口 IP。请接好打印机后重新运行配置向导。");
        }
        config.ConnectionMode = "net";
        config.NetworkHost = ip.Trim();
        config.NetworkPort = ReadInt("打印机端口", previous.NetworkPort <= 0 ? 9100 : previous.NetworkPort);
        config.UsbPath = "";
    }

    private static AgentConfig TryLoadConfig()
    {
        try
        {
            bool migrated;
            AgentConfig config = DeserializeConfig(File.ReadAllText(ConfigPath, Encoding.UTF8), out migrated);
            if (migrated)
            {
                SaveConfig(config);
            }
            return config;
        }
        catch
        {
            return new AgentConfig();
        }
    }

    private static void Run(AgentConfig config)
    {
        ValidateConfig(config);
        Console.WriteLine("打印代理已启动");
        Console.WriteLine("  后端：" + config.ApiBaseUrl);
        Console.WriteLine("  打印机：" + ConnectionDescription(config));
        Console.WriteLine("  轮询间隔：" + config.PollSeconds + " 秒");
        AgentLog.Write("代理启动，后端=" + config.ApiBaseUrl + "，连接=" + ConnectionDescription(config));

        Console.WriteLine();
        Console.Write("正在连接后端……");
        try
        {
            ApiClient.Heartbeat(config);
            Console.WriteLine(" 连接成功！代理已上线。");
            Console.WriteLine("等待管理后台推送打印任务……");
            Console.WriteLine();
        }
        catch (Exception exception)
        {
            Console.WriteLine();
            Console.WriteLine("!!! 后端连接失败：" + exception.Message);
            Console.WriteLine("!!! 代理将继续尝试连接，但当前无法接收打印任务。");
            Console.WriteLine("!!! 请检查：");
            Console.WriteLine("!!!   1. API 地址是否正确（当前：" + config.ApiBaseUrl + "）");
            Console.WriteLine("!!!   2. 打印密钥是否正确（重新运行 setup 可更新配置）");
            Console.WriteLine("!!!   3. 后端服务是否已启动");
            Console.WriteLine("!!!   4. 防火墙是否放行了端口");
            Console.WriteLine();
        }

        while (true)
        {
            try
            {
                Dictionary<string, object> job = ApiClient.NextJob(config);
                if (job != null)
                {
                    ExecuteJob(config, job);
                }
            }
            catch (Exception exception)
            {
                AgentLog.Write("轮询失败: " + exception.Message);
            }
            Thread.Sleep(Math.Max(2, config.PollSeconds) * 1000);
        }
    }

    private static void ExecuteJob(AgentConfig config, Dictionary<string, object> job)
    {
        long jobId = ReadLong(job, "id");
        string leaseToken = ReadString(job, "leaseToken");
        try
        {
            using (XprinterClient printer = new XprinterClient(config))
            {
                printer.Connect();
                printer.Print(ToDictionary(job, "receipt"));
            }
            ApiClient.CompleteJob(config, jobId, leaseToken, true, "打印完成");
            AgentLog.Write("打印完成，任务=" + jobId);
        }
        catch (Exception exception)
        {
            string message = TrimMessage(exception.Message);
            try
            {
                ApiClient.CompleteJob(config, jobId, leaseToken, false, message);
            }
            catch (Exception callbackException)
            {
                AgentLog.Write("任务=" + jobId + " 打印失败且回执失败: " + callbackException.Message);
            }
            AgentLog.Write("打印失败，任务=" + jobId + "，原因=" + message);
        }
    }

    private static void ScanUsb()
    {
        List<string> devices = FindUsbDevices();
        if (devices.Count == 0)
        {
            Console.WriteLine("未发现 USB 打印机。请确认电源、数据线，并在此电脑安装芯烨 58 系列 USB 驱动。");
            return;
        }
        foreach (string device in devices)
        {
            Console.WriteLine("USB," + device);
        }
    }

    private static List<string> FindUsbDevices()
    {
        List<string> devices = new List<string>();
        Native.DeviceCallback callback = delegate(IntPtr pointer)
        {
            string device = Marshal.PtrToStringAnsi(pointer);
            if (!String.IsNullOrWhiteSpace(device) && !devices.Contains(device))
            {
                devices.Add(device);
            }
        };
        int code = Native.FindPrinters("USB,", callback);
        Thread.Sleep(1500);
        if (code != 0)
        {
            throw new InvalidOperationException("USB 扫描失败，错误码：" + code);
        }
        return devices;
    }

    private static AgentConfig LoadConfig()
    {
        if (!File.Exists(ConfigPath))
        {
            throw new InvalidOperationException("未找到 agent.config.json，请先执行 YulinPrintAgent.exe setup");
        }
        bool migrated;
        AgentConfig config = DeserializeConfig(File.ReadAllText(ConfigPath, Encoding.UTF8), out migrated);
        if (migrated)
        {
            SaveConfig(config);
            AgentLog.Write("旧版明文打印密钥已迁移为当前 Windows 用户的 DPAPI 密文");
        }
        ValidateConfig(config);
        return config;
    }

    private static void ValidateConfig(AgentConfig config)
    {
        if (String.IsNullOrWhiteSpace(config.ApiBaseUrl) || String.IsNullOrWhiteSpace(config.AccessKey))
        {
            throw new InvalidOperationException("API 地址和打印代理密钥不能为空");
        }
        ValidateApiUrl(config.ApiBaseUrl);
        if (String.Equals(config.ConnectionMode, "usb", StringComparison.OrdinalIgnoreCase))
        {
            if (String.IsNullOrWhiteSpace(config.UsbPath))
            {
                throw new InvalidOperationException("USB 设备路径不能为空");
            }
            return;
        }
        if (String.IsNullOrWhiteSpace(config.NetworkHost))
        {
            throw new InvalidOperationException("打印机局域网 IP 不能为空");
        }
        if (config.NetworkPort <= 0 || config.NetworkPort > 65535)
        {
            throw new InvalidOperationException("打印机端口不合法");
        }
    }

    private static void ValidateApiUrl(string value)
    {
        Uri uri;
        if (!Uri.TryCreate(value, UriKind.Absolute, out uri))
        {
            throw new InvalidOperationException("API 地址不是有效的绝对 URL");
        }

        bool isHttps = String.Equals(uri.Scheme, Uri.UriSchemeHttps, StringComparison.OrdinalIgnoreCase);
        bool isLocalHttp = String.Equals(uri.Scheme, Uri.UriSchemeHttp, StringComparison.OrdinalIgnoreCase)
            && (uri.IsLoopback || String.Equals(uri.Host, "localhost", StringComparison.OrdinalIgnoreCase));
        if (!isHttps && !isLocalHttp)
        {
            throw new InvalidOperationException("生产 API 必须使用 HTTPS；HTTP 仅允许 localhost/回环地址");
        }
        if (!String.IsNullOrEmpty(uri.UserInfo))
        {
            throw new InvalidOperationException("API 地址不能包含用户名或密码");
        }
    }

    private static AgentConfig DeserializeConfig(string json, out bool migrated)
    {
        migrated = false;
        AgentConfig config = Json.Deserialize<AgentConfig>(json);
        if (config == null)
        {
            throw new InvalidOperationException("agent.config.json 读取失败");
        }

        if (!String.IsNullOrWhiteSpace(config.AccessKeyProtected))
        {
            config.AccessKey = UnprotectSecret(config.AccessKeyProtected);
            return config;
        }

        Dictionary<string, object> values = Json.DeserializeObject(json) as Dictionary<string, object>;
        string legacyAccessKey = ReadString(values, "AccessKey");
        if (String.IsNullOrWhiteSpace(legacyAccessKey))
        {
            legacyAccessKey = ReadString(values, "accessKey");
        }
        if (!String.IsNullOrWhiteSpace(legacyAccessKey))
        {
            config.AccessKey = legacyAccessKey;
            migrated = true;
        }
        return config;
    }

    private static void SaveConfig(AgentConfig config)
    {
        if (String.IsNullOrWhiteSpace(config.AccessKey))
        {
            throw new InvalidOperationException("打印代理密钥不能为空");
        }
        config.AccessKeyProtected = ProtectSecret(config.AccessKey);
        string temporaryPath = ConfigPath + ".tmp";
        File.WriteAllText(temporaryPath, Json.Serialize(config), new UTF8Encoding(false));
        if (File.Exists(ConfigPath))
        {
            File.Replace(temporaryPath, ConfigPath, null);
        }
        else
        {
            File.Move(temporaryPath, ConfigPath);
        }
    }

    private static string ProtectSecret(string value)
    {
        byte[] plainText = Encoding.UTF8.GetBytes(value);
        byte[] protectedBytes = ProtectedData.Protect(plainText, KeyEntropy, DataProtectionScope.CurrentUser);
        return Convert.ToBase64String(protectedBytes);
    }

    private static string UnprotectSecret(string value)
    {
        try
        {
            byte[] protectedBytes = Convert.FromBase64String(value);
            byte[] plainText = ProtectedData.Unprotect(protectedBytes, KeyEntropy, DataProtectionScope.CurrentUser);
            return Encoding.UTF8.GetString(plainText);
        }
        catch (Exception exception)
        {
            throw new InvalidOperationException(
                "打印密钥无法解密。配置只能由创建它的 Windows 用户使用，请用该用户运行或重新执行 setup。",
                exception);
        }
    }

    private static Dictionary<string, object> BuildSelfTestReceipt()
    {
        Dictionary<string, object> receipt = new Dictionary<string, object>();
        receipt["storeName"] = "禹邻优鲜";
        receipt["title"] = "打印机测试";
        receipt["orderNo"] = "SELF-TEST";
        receipt["createdAt"] = DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss");
        receipt["deliverySlot"] = "测试时段";
        receipt["customerName"] = "门店测试";
        receipt["customerPhone"] = "";
        receipt["address"] = "芯烨 XP-58III NT";
        receipt["items"] = new ArrayList { new Dictionary<string, object> { { "name", "测试商品" }, { "quantity", "1份" }, { "unitPrice", "¥ 0.01" }, { "amount", "¥ 0.01" } } };
        receipt["productAmount"] = "¥ 0.01";
        receipt["deliveryFee"] = "¥ 0.00";
        receipt["packageFee"] = "¥ 0.00";
        receipt["payableAmount"] = "¥ 0.01";
        receipt["remark"] = "打印正常即表示已可自动接单打印。";
        return receipt;
    }

    private static string ReadValue(string label, string defaultValue)
    {
        Console.Write(label + (String.IsNullOrWhiteSpace(defaultValue) ? "：" : " [" + defaultValue + "]："));
        string value = Console.ReadLine();
        return String.IsNullOrWhiteSpace(value) ? defaultValue : value.Trim();
    }

    private static string ReadRequired(string label, string defaultValue)
    {
        while (true)
        {
            string value = ReadValue(label, defaultValue);
            if (!String.IsNullOrWhiteSpace(value))
            {
                return value;
            }
            Console.WriteLine("* " + label + "不能为空，请重新输入。");
        }
    }

    private static string ReadSecretOrExisting(string label, string existingValue)
    {
        while (true)
        {
            Console.Write(label + (String.IsNullOrWhiteSpace(existingValue) ? "：" : "（直接回车保留现有密钥）："));
            string value = Console.ReadLine();
            if (!String.IsNullOrWhiteSpace(value))
            {
                return value.Trim();
            }
            if (!String.IsNullOrWhiteSpace(existingValue))
            {
                return existingValue;
            }
            Console.WriteLine("* " + label + "不能为空，请重新输入。");
        }
    }


    private static int ReadInt(string label, int defaultValue)
    {
        string value = ReadValue(label, defaultValue.ToString());
        int result;
        return Int32.TryParse(value, out result) ? result : defaultValue;
    }

    private static string ConnectionDescription(AgentConfig config)
    {
        return String.Equals(config.ConnectionMode, "usb", StringComparison.OrdinalIgnoreCase)
            ? "USB," + config.UsbPath
            : "NET," + config.NetworkHost + "," + config.NetworkPort;
    }

    private static string ValueOr(string value, string fallback)
    {
        return String.IsNullOrWhiteSpace(value) ? fallback : value;
    }

    private static Dictionary<string, object> ToDictionary(Dictionary<string, object> source, string key)
    {
        object value;
        if (source == null || !source.TryGetValue(key, out value) || value == null)
        {
            return new Dictionary<string, object>();
        }
        Dictionary<string, object> result = value as Dictionary<string, object>;
        return result ?? new Dictionary<string, object>();
    }

    private static string ReadString(IDictionary<string, object> source, string key)
    {
        object value;
        return source != null && source.TryGetValue(key, out value) && value != null ? Convert.ToString(value) : "";
    }

    private static bool HasPositiveMoney(string value)
    {
        decimal amount;
        string normalized = (value ?? "")
            .Replace("¥", "")
            .Replace("￥", "")
            .Trim();
        return Decimal.TryParse(normalized, NumberStyles.Number, CultureInfo.InvariantCulture, out amount)
            && amount > 0M;
    }

    private static long ReadLong(IDictionary<string, object> source, string key)
    {
        object value;
        if (source == null || !source.TryGetValue(key, out value) || value == null)
        {
            return 0L;
        }
        return Convert.ToInt64(value);
    }

    private static string TrimMessage(string message)
    {
        string normalized = String.IsNullOrWhiteSpace(message) ? "打印失败" : message.Trim();
        return normalized.Length > 240 ? normalized.Substring(0, 240) : normalized;
    }

    private sealed class AgentConfig
    {
        public string ApiBaseUrl { get; set; }
        public string AccessKeyProtected { get; set; }
        [ScriptIgnore]
        public string AccessKey { get; set; }
        public string ConnectionMode { get; set; }
        public string NetworkHost { get; set; }
        public int NetworkPort { get; set; }
        public string UsbPath { get; set; }
        public bool CutPaper { get; set; }
        public int PollSeconds { get; set; }
    }

    private sealed class XprinterClient : IDisposable
    {
        private readonly AgentConfig config;
        private IntPtr handle;
        private bool connected;

        public XprinterClient(AgentConfig config)
        {
            this.config = config;
        }

        public void Connect()
        {
            int createCode = Native.PrinterCreator(out handle, "");
            if (createCode != 0 || handle == IntPtr.Zero)
            {
                throw new InvalidOperationException("初始化芯烨 SDK 失败，错误码：" + createCode);
            }
            string setting = ConnectionDescription(config);
            int openCode = Native.OpenPortA(handle, setting);
            if (openCode != 0)
            {
                Dispose();
                throw new InvalidOperationException("连接打印机失败，错误码：" + openCode + "，连接=" + setting);
            }
            connected = true;
            EnsureReady();
        }

        public void Print(Dictionary<string, object> receipt)
        {
            if (!connected)
            {
                throw new InvalidOperationException("打印机尚未连接");
            }
            int initializeCode = Native.PrinterInitialize(handle);
            if (initializeCode != 0)
            {
                throw new InvalidOperationException("初始化打印机失败，错误码：" + initializeCode);
            }
            Send(new byte[] { 0x1B, 0x61, 0x01 });
            Send(new byte[] { 0x1B, 0x45, 0x01 });
            WriteWrapped(ReadString(receipt, "storeName"), 32);
            Send(new byte[] { 0x1B, 0x45, 0x00 });
            WriteWrapped(ReadString(receipt, "title"), 32);
            Line();
            Send(new byte[] { 0x1B, 0x61, 0x00 });
            WritePair("订单号", ReadString(receipt, "orderNo"));
            WritePair("下单时间", ReadString(receipt, "createdAt"));
            WritePair("配送时段", ReadString(receipt, "deliverySlot"));
            WritePair("收货人", ReadString(receipt, "customerName") + " " + ReadString(receipt, "customerPhone"));
            WriteWrapped("地址：" + ReadString(receipt, "address"), 32);
            Line();
            WriteText("商品明细\r\n");
            object rawItems;
            if (receipt.TryGetValue("items", out rawItems) && rawItems is IEnumerable)
            {
                foreach (object itemValue in (IEnumerable)rawItems)
                {
                    Dictionary<string, object> item = itemValue as Dictionary<string, object>;
                    if (item == null)
                    {
                        continue;
                    }
                    WriteWrapped(ReadString(item, "name"), 32);
                    WritePair(ReadString(item, "quantity") + " x " + ReadString(item, "unitPrice"), ReadString(item, "amount"));
                }
            }
            object rawGifts;
            if (receipt.TryGetValue("gifts", out rawGifts) && rawGifts is IEnumerable)
            {
                bool giftHeadingPrinted = false;
                foreach (object giftValue in (IEnumerable)rawGifts)
                {
                    Dictionary<string, object> gift = giftValue as Dictionary<string, object>;
                    if (gift == null)
                    {
                        continue;
                    }
                    if (!giftHeadingPrinted)
                    {
                        Line();
                        Send(new byte[] { 0x1B, 0x45, 0x01 });
                        WriteText("鲜礼赠品（随单配送）\r\n");
                        Send(new byte[] { 0x1B, 0x45, 0x00 });
                        giftHeadingPrinted = true;
                    }
                    WriteWrapped(ReadString(gift, "name"), 32);
                    string giftUnitPrice = ReadString(gift, "unitPrice");
                    string giftQuantity = ReadString(gift, "quantity");
                    WritePair(
                        HasPositiveMoney(giftUnitPrice) ? giftQuantity + " x " + giftUnitPrice : giftQuantity,
                        ValueOr(ReadString(gift, "amount"), "¥ 0.00"));
                }
            }
            Line();
            WritePair("商品金额", ReadString(receipt, "productAmount"));
            WritePair("配送费", ReadString(receipt, "deliveryFee"));
            WritePair("包装费", ReadString(receipt, "packageFee"));
            string discountAmount = ReadString(receipt, "discountAmount");
            if (HasPositiveMoney(discountAmount))
            {
                WritePair("随机减免", "- " + discountAmount);
            }
            Send(new byte[] { 0x1B, 0x45, 0x01 });
            WritePair("实付款", ReadString(receipt, "payableAmount"));
            Send(new byte[] { 0x1B, 0x45, 0x00 });
            string remark = ReadString(receipt, "remark");
            if (!String.IsNullOrWhiteSpace(remark) && remark != "无")
            {
                Line();
                WriteWrapped("备注：" + remark, 32);
            }
            Line();
            Send(new byte[] { 0x1B, 0x61, 0x01 });
            WriteText("感谢您的购买\r\n");
            WriteText("\r\n\r\n\r\n");
            if (config.CutPaper)
            {
                int cutCode = Native.CutPaperWithDistance(handle, 10);
                if (cutCode != 0)
                {
                    AgentLog.Write("切纸未执行，错误码：" + cutCode);
                }
            }
        }

        private void EnsureReady()
        {
            uint status;
            int code = Native.GetPrinterStateII(handle, out status);
            if (code != 0)
            {
                return;
            }
            if ((status & 0x04) != 0)
            {
                throw new InvalidOperationException("打印机缺纸");
            }
            if ((status & 0x20) != 0)
            {
                throw new InvalidOperationException("打印机处于错误状态");
            }
            if ((status & 0x40) != 0)
            {
                throw new InvalidOperationException("打印机切刀异常");
            }
            if ((status & 0x80) != 0)
            {
                throw new InvalidOperationException("打印机机头温度异常");
            }
        }

        private void Send(byte[] buffer)
        {
            int code = Native.ESC_SendData(handle, buffer, buffer.Length);
            if (code != 0)
            {
                throw new InvalidOperationException("发送打印数据失败，错误码：" + code);
            }
        }

        private void WriteText(string text)
        {
            if (!String.IsNullOrEmpty(text))
            {
                Send(Encoding.GetEncoding(936).GetBytes(text));
            }
        }

        private void WritePair(string left, string right)
        {
            WriteText(FitPair(left, right, 32) + "\r\n");
        }

        private void WriteWrapped(string value, int width)
        {
            string source = String.IsNullOrWhiteSpace(value) ? "-" : value.Trim();
            StringBuilder line = new StringBuilder();
            int cells = 0;
            foreach (char character in source)
            {
                int charWidth = CharacterWidth(character);
                if (cells + charWidth > width && line.Length > 0)
                {
                    WriteText(line.ToString() + "\r\n");
                    line.Clear();
                    cells = 0;
                }
                line.Append(character);
                cells += charWidth;
            }
            if (line.Length > 0)
            {
                WriteText(line.ToString() + "\r\n");
            }
        }

        private void Line()
        {
            WriteText("--------------------------------\r\n");
        }

        public void Dispose()
        {
            if (handle != IntPtr.Zero)
            {
                if (connected)
                {
                    Native.ClosePort(handle);
                }
                Native.ReleasePrinter(handle);
                handle = IntPtr.Zero;
                connected = false;
            }
        }

        private static string FitPair(string left, string right, int width)
        {
            string normalizedLeft = left ?? "";
            string normalizedRight = right ?? "";
            int rightWidth = DisplayWidth(normalizedRight);
            int available = Math.Max(1, width - rightWidth - 1);
            normalizedLeft = TrimToWidth(normalizedLeft, available);
            int spaces = Math.Max(1, width - DisplayWidth(normalizedLeft) - rightWidth);
            return normalizedLeft + new string(' ', spaces) + normalizedRight;
        }

        private static string TrimToWidth(string value, int maxWidth)
        {
            StringBuilder result = new StringBuilder();
            int cells = 0;
            foreach (char character in value)
            {
                int width = CharacterWidth(character);
                if (cells + width > maxWidth)
                {
                    break;
                }
                result.Append(character);
                cells += width;
            }
            return result.ToString();
        }

        private static int DisplayWidth(string value)
        {
            int width = 0;
            foreach (char character in value ?? "")
            {
                width += CharacterWidth(character);
            }
            return width;
        }

        private static int CharacterWidth(char character)
        {
            return character <= 0x7F ? 1 : 2;
        }
    }

    private static class ApiClient
    {
        public static Dictionary<string, object> NextJob(AgentConfig config)
        {
            Dictionary<string, object> response = Send(config, "GET", "/printing/agent/jobs/next", null);
            object data;
            return response.TryGetValue("data", out data) ? data as Dictionary<string, object> : null;
        }

        public static void CompleteJob(AgentConfig config, long id, string leaseToken, bool success, string message)
        {
            Dictionary<string, object> payload = new Dictionary<string, object>();
            payload["leaseToken"] = leaseToken;
            payload["success"] = success;
            payload["message"] = message;
            Send(config, "POST", "/printing/agent/jobs/" + id + "/result", payload);
        }

        public static void Heartbeat(AgentConfig config)
        {
            Dictionary<string, object> payload = new Dictionary<string, object>();
            payload["agentName"] = Environment.MachineName;
            payload["connection"] = ConnectionDescription(config);
            payload["version"] = Version;
            Send(config, "POST", "/printing/agent/heartbeat", payload);
        }

        private static Dictionary<string, object> Send(AgentConfig config, string method, string relativePath, object payload)
        {
            ServicePointManager.SecurityProtocol = SecurityProtocolType.Tls12;
            string url = config.ApiBaseUrl.TrimEnd('/') + relativePath;
            HttpWebRequest request = (HttpWebRequest)WebRequest.Create(url);
            request.Method = method;
            request.Timeout = 15000;
            request.ReadWriteTimeout = 15000;
            request.Accept = "application/json";
            request.Headers["X-Printer-Agent-Key"] = config.AccessKey;
            request.Headers["X-Printer-Agent-Name"] = Environment.MachineName;
            request.Headers["X-Printer-Agent-Connection"] = ConnectionDescription(config);
            request.Headers["X-Printer-Agent-Version"] = Version;
            if (payload != null)
            {
                byte[] body = Encoding.UTF8.GetBytes(Json.Serialize(payload));
                request.ContentType = "application/json; charset=utf-8";
                request.ContentLength = body.Length;
                using (Stream stream = request.GetRequestStream())
                {
                    stream.Write(body, 0, body.Length);
                }
            }
            try
            {
                using (HttpWebResponse response = (HttpWebResponse)request.GetResponse())
                using (StreamReader reader = new StreamReader(response.GetResponseStream(), Encoding.UTF8))
                {
                    return ParseResponse(reader.ReadToEnd());
                }
            }
            catch (WebException exception)
            {
                string responseText = "";
                if (exception.Response != null)
                {
                    using (StreamReader reader = new StreamReader(exception.Response.GetResponseStream(), Encoding.UTF8))
                    {
                        responseText = reader.ReadToEnd();
                    }
                }
                throw new InvalidOperationException("接口请求失败：" + TrimMessage(responseText.Length == 0 ? exception.Message : responseText));
            }
        }

        private static Dictionary<string, object> ParseResponse(string responseText)
        {
            Dictionary<string, object> response = Json.DeserializeObject(responseText) as Dictionary<string, object>;
            if (response == null)
            {
                throw new InvalidOperationException("接口返回格式错误");
            }
            object code;
            if (response.TryGetValue("code", out code) && Convert.ToInt32(code) != 0)
            {
                throw new InvalidOperationException(ReadString(response, "message"));
            }
            return response;
        }
    }

    private static class AgentLog
    {
        private static readonly object Locker = new object();
        private static readonly string LogPath = Path.Combine(BaseDirectory, "print-agent.log");
        private const long MaxLogBytes = 5L * 1024L * 1024L;
        private const int RetainedLogFiles = 5;

        public static void Write(string message)
        {
            try
            {
                lock (Locker)
                {
                    string line = DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss") + " " + message + Environment.NewLine;
                    RotateIfNeeded(Encoding.UTF8.GetByteCount(line));
                    File.AppendAllText(LogPath, line, new UTF8Encoding(false));
                }
            }
            catch
            {
                // Logging must never terminate the unattended print loop.
            }
        }

        private static void RotateIfNeeded(int incomingBytes)
        {
            if (!File.Exists(LogPath) || new FileInfo(LogPath).Length + incomingBytes <= MaxLogBytes)
            {
                return;
            }

            string oldest = LogPath + "." + RetainedLogFiles;
            if (File.Exists(oldest))
            {
                File.Delete(oldest);
            }
            for (int index = RetainedLogFiles - 1; index >= 1; index--)
            {
                string source = LogPath + "." + index;
                string destination = LogPath + "." + (index + 1);
                if (File.Exists(source))
                {
                    if (File.Exists(destination))
                    {
                        File.Delete(destination);
                    }
                    File.Move(source, destination);
                }
            }
            File.Move(LogPath, LogPath + ".1");
        }
    }

    private static class Native
    {
        [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
        internal delegate void DeviceCallback(IntPtr value);

        [DllImport("printer.sdk.dll", CallingConvention = CallingConvention.Cdecl, CharSet = CharSet.Ansi)]
        internal static extern int PrinterCreator(out IntPtr handle, string model);

        [DllImport("printer.sdk.dll", CallingConvention = CallingConvention.Cdecl, CharSet = CharSet.Ansi)]
        internal static extern int OpenPortA(IntPtr handle, string setting);

        [DllImport("printer.sdk.dll", CallingConvention = CallingConvention.Cdecl)]
        internal static extern int ClosePort(IntPtr handle);

        [DllImport("printer.sdk.dll", CallingConvention = CallingConvention.Cdecl)]
        internal static extern int ReleasePrinter(IntPtr handle);

        [DllImport("printer.sdk.dll", CallingConvention = CallingConvention.Cdecl)]
        internal static extern int PrinterInitialize(IntPtr handle);

        [DllImport("printer.sdk.dll", CallingConvention = CallingConvention.Cdecl)]
        internal static extern int GetPrinterStateII(IntPtr handle, out UInt32 printerStatus);

        [DllImport("printer.sdk.dll", CallingConvention = CallingConvention.Cdecl)]
        internal static extern int ESC_SendData(IntPtr handle, byte[] buffer, int size);

        [DllImport("printer.sdk.dll", CallingConvention = CallingConvention.Cdecl)]
        internal static extern int CutPaperWithDistance(IntPtr handle, int distance);

        [DllImport("printer.sdk.dll", CallingConvention = CallingConvention.Cdecl, CharSet = CharSet.Ansi)]
        internal static extern int FindPrinters(string type, DeviceCallback callback);
    }
}