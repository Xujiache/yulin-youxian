using System;
using System.Collections;
using System.Collections.Generic;
using System.IO;
using System.Net;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Web.Script.Serialization;

internal static class Program
{
    private const string Version = "1.0.0";
    private static readonly JavaScriptSerializer Json = new JavaScriptSerializer { MaxJsonLength = Int32.MaxValue };
    private static readonly string BaseDirectory = AppDomain.CurrentDomain.BaseDirectory;
    private static readonly string ConfigPath = Path.Combine(BaseDirectory, "agent.config.json");

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
            Console.Error.WriteLine("启动失败: " + exception.Message);
            return 1;
        }
    }

    private static void Setup()
    {
        Console.WriteLine("禹邻优鲜芯烨小票打印代理 " + Version);
        AgentConfig previous = File.Exists(ConfigPath) ? LoadConfig() : new AgentConfig();
        AgentConfig config = new AgentConfig();
        config.ApiBaseUrl = ReadValue("后端 API 地址", ValueOr(previous.ApiBaseUrl, "https://hqhjxt.vip/api"));
        config.AccessKey = ReadValue("后台生成的打印代理密钥", previous.AccessKey);
        config.ConnectionMode = ReadValue("连接方式（net 或 usb）", ValueOr(previous.ConnectionMode, "net")).ToLowerInvariant();
        if (config.ConnectionMode == "usb")
        {
            Console.WriteLine("可先执行 YulinPrintAgent.exe scan 查找 USB 设备路径。");
            config.UsbPath = ReadValue("USB 设备路径", previous.UsbPath);
            config.NetworkHost = "";
            config.NetworkPort = 9100;
        }
        else
        {
            config.ConnectionMode = "net";
            config.NetworkHost = ReadValue("打印机局域网 IP", previous.NetworkHost);
            config.NetworkPort = ReadInt("打印机端口", previous.NetworkPort <= 0 ? 9100 : previous.NetworkPort);
            config.UsbPath = "";
        }
        config.CutPaper = ReadBoolean("每单执行切纸", previous.CutPaper);
        config.PollSeconds = Math.Max(2, ReadInt("轮询秒数", previous.PollSeconds <= 0 ? 3 : previous.PollSeconds));
        ValidateConfig(config);
        File.WriteAllText(ConfigPath, Json.Serialize(config), new UTF8Encoding(false));
        Console.WriteLine("配置已保存：" + ConfigPath);
        Console.WriteLine("执行 YulinPrintAgent.exe self-test 可测试硬件；直接执行 YulinPrintAgent.exe 可启动自动打印。");
    }

    private static void Run(AgentConfig config)
    {
        ValidateConfig(config);
        Console.WriteLine("打印代理已启动：" + ConnectionDescription(config));
        AgentLog.Write("代理启动，连接=" + ConnectionDescription(config));
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

    private static AgentConfig LoadConfig()
    {
        if (!File.Exists(ConfigPath))
        {
            throw new InvalidOperationException("未找到 agent.config.json，请先执行 YulinPrintAgent.exe setup");
        }
        AgentConfig config = Json.Deserialize<AgentConfig>(File.ReadAllText(ConfigPath, Encoding.UTF8));
        if (config == null)
        {
            throw new InvalidOperationException("agent.config.json 读取失败");
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

    private static int ReadInt(string label, int defaultValue)
    {
        string value = ReadValue(label, defaultValue.ToString());
        int result;
        return Int32.TryParse(value, out result) ? result : defaultValue;
    }

    private static bool ReadBoolean(string label, bool defaultValue)
    {
        string value = ReadValue(label + "（y/n）", defaultValue ? "y" : "n");
        return String.Equals(value, "y", StringComparison.OrdinalIgnoreCase) || String.Equals(value, "yes", StringComparison.OrdinalIgnoreCase) || value == "1" || value == "是";
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
                    WritePair(ReadString(item, "quantity") + " × " + ReadString(item, "unitPrice"), ReadString(item, "amount"));
                }
            }
            Line();
            WritePair("商品金额", ReadString(receipt, "productAmount"));
            WritePair("配送费", ReadString(receipt, "deliveryFee"));
            WritePair("包装费", ReadString(receipt, "packageFee"));
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

        private static Dictionary<string, object> Send(AgentConfig config, string method, string relativePath, object payload)
        {
            ServicePointManager.SecurityProtocol = SecurityProtocolType.Tls12 | SecurityProtocolType.Tls11;
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

        public static void Write(string message)
        {
            lock (Locker)
            {
                File.AppendAllText(LogPath, DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss") + " " + message + Environment.NewLine, new UTF8Encoding(false));
            }
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
