# Android 骑手端设计

## 1. 技术栈（冻结）

| 项 | 选型 | 版本 |
| --- | --- | --- |
| 语言 | Kotlin | 2.3.21 |
| 构建 | Gradle Kotlin DSL + Version Catalog (`gradle/libs.versions.toml`) | AGP 8.13+ |
| `minSdk` / `targetSdk` / `compileSdk` | 26 / 36 / 36 | —— |
| UI | Jetpack Compose + Material 3 | Compose BOM 2026.06.01 |
| 架构 | MVVM + 单向数据流（`StateFlow<UiState>`） | —— |
| DI | Hilt | 2.59.2 |
| 本地库 | Room | 2.8.4 |
| 偏好 | DataStore Preferences | 1.2.1 |
| 后台任务 | WorkManager | 2.11.2 |
| 网络 | Retrofit + OkHttp + kotlinx.serialization | Retrofit 3.0.0 |
| 导航 | Navigation Compose | 2.9.8 |
| 图片 | Coil | 3.x |
| 地图/定位/导航 | 高德 Android SDK（3D 地图 + 定位 + 导航） | 定位/导航 11.2.100 |
| 推送 | 极光 JPush（含全厂商通道） | 最新稳定 |
| 语音 | Android `TextToSpeech` + 自定义提示音 | —— |
| 测试 | JUnit5 + Turbine + MockK + Compose UI Test | —— |

`minSdk = 26` 的理由：通知渠道（自定义派单提示音必需）从 API 26 才有，而派单提醒是骑手端的命脉。

**Kotlin 2.0 之后 Compose 编译器由 `org.jetbrains.kotlin.plugin.compose` 插件管理，不需要手动对齐编译器版本。**

## 2. 模块划分

多模块，因为要给多个 Agent 并行开发，模块边界就是文件归属边界。

```
rider-android/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle/libs.versions.toml          ★ 版本目录，Wave 0 定稿后其他 Agent 只读
├── app/                               壳：Application、MainActivity、导航图、权限编排
├── core/
│   ├── common/                        Result、扩展、时间/距离格式化、协程调度器
│   ├── designsystem/                  主题、色板、字号、间距、通用组件（大按钮、滑动确认、状态标签）
│   ├── network/                       Retrofit、拦截器、DTO、ApiResponse 解包、错误映射
│   ├── database/                      Room：任务缓存、位置缓冲、离线操作队列
│   ├── datastore/                     令牌、用户偏好、保活向导完成标记
│   ├── model/                         领域模型（与网络 DTO 分离）
│   ├── location/                      前台定位服务、采样策略、上传器、保活向导
│   └── push/                          JPush 接入、通知渠道、语音播报
├── feature/
│   ├── auth/                          登录、改密、定位同意
│   ├── shift/                         上下班、上班检查清单、疲劳弹窗
│   ├── task/                          任务列表、波次视图、任务详情、状态流转、取货核对、送达
│   ├── map/                           地图页、路线渲染、导航跳转、顺序调整
│   ├── exception/                     异常上报、拍照
│   ├── earning/                       今日统计（结算、服务分、申诉三页已从导航图移除，见 §4）
│   ├── message/                       消息中心
│   └── profile/                       个人中心、设置、保活向导入口、关于（备案号展示）
└── buildSrc 或 build-logic/           约定插件（可选）
```

依赖方向严格单向：`app → feature → core`，`feature` 之间**不互相依赖**。跨 feature 的跳转通过 `app` 层的导航图。

## 3. 关键设计

### 3.1 定位管道（最重要的部分）

```
┌────────────────────────────────────────────────────────┐
│ LocationForegroundService                              │
│  foregroundServiceType="location"                      │
│  常驻高优先级通知："配送中 · 已在线 4 小时 · 3 单进行中"  │
│                                                         │
│  AMapLocationClient ──► LocationSampler ──► Room        │
│                          (自适应间隔)      (缓冲表)      │
│                                                         │
│  LocationUploader (协程，每 20 秒)                       │
│      读 Room 未上传点 → POST /locations/batch           │
│      成功 → 标记已上传 → 定期清理                        │
│      失败 → 保留，下次重试（指数退避，上限 60 秒）        │
└────────────────────────────────────────────────────────┘
```

**为什么不用 `FusedLocationProviderClient`**：Google Play 服务在国内设备上普遍缺失或残缺。高德定位 SDK 原生做 GPS + WiFi + 基站融合。

**为什么定位不放 ViewModel**：Activity 会被销毁，骑手锁屏骑车时 UI 早就没了。Service 持有 Repository，Repository 写 Room，UI 观察同一个 Repository。

**自适应采样**（`LocationSampler`）：

| 运动状态 | 判定 | 采样间隔 |
| --- | --- | --- |
| `STILL` | 连续 3 个点位移 < 10 m | 60 秒 |
| `WALKING` | 速度 0.5–2.5 m/s | 20 秒 |
| `RIDING` | 速度 > 2.5 m/s | 10 秒 |

间隔可被服务端覆盖（`/locations/batch` 响应里的 `nextIntervalSeconds`）。自适应采样同时是省电措施**和** PIPL 最小必要原则的合规论据。

**轨迹清洗**（上报前在端上做一遍，服务端再做一遍）：
- 精度差于 100 m 的点直接丢弃
- 与上一点的推算速度 > 30 m/s 的点判为跳变，丢弃
- 简单卡尔曼平滑

### 3.2 保活四层（决定 App 能不能用）

**第一层 · 平台合规**

```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.WAKE_LOCK" />

<service android:name=".LocationForegroundService"
         android:foregroundServiceType="location"
         android:exported="false" />
```

`location` 类型的前台服务**没有运行时长上限**（6 小时限制只针对 `dataSync`），可以跑完整个班次。

**第二层 · 权限时序**

定位服务**只能由骑手在 App 可见时点击「上班」启动**。绝不从推送、开机广播或后台任务启动——那会撞上 while-in-use 限制直接抛 `SecurityException`。

权限申请分三步，每步都有说明文案（PIPL 要求告知目的）：
1. 前台定位（`ACCESS_FINE_LOCATION`）+ 通知（`POST_NOTIFICATIONS`）
2. 独立的定位授权同意页 → 调 `/auth/location-consent`
3. 后台定位（`ACCESS_BACKGROUND_LOCATION`）—— 必须单独申请，且系统会跳到设置页

**第三层 · 国产 ROM 保活向导**

这是一个完整的功能页面，不是弹窗。按 `Build.MANUFACTURER` 分支：

| 厂商 | 需引导的设置 | Intent 目标（best-effort，失败回退到应用详情页） |
| --- | --- | --- |
| Xiaomi / Redmi | 自启动、省电策略→无限制、后台弹出界面 | `com.miui.securitycenter/.permission.PermMainActiivty` 等 |
| HUAWEI / HONOR | 应用启动管理→手动管理（三项全开）、忽略电池优化 | `com.huawei.systemmanager/.startupmgr.ui.StartupNormalAppListActivity` |
| OPPO / realme / OnePlus | 自启动、允许后台活动、耗电保护 | `com.coloros.safecenter/...` |
| vivo / iQOO | 后台高耗电、自启动、锁定后台 | `com.vivo.permissionmanager/...` |
| Samsung | 未监视的应用 | —— |
| 其他 | 忽略电池优化通用页 | `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` |

**实现要点**：
- 每一项配一张示意图和一句话说明。骑手不是工程师。
- 深链 Intent 必须 `try/catch`，ROM 版本一变类名就没了。失败时回退到 `ACTION_APPLICATION_DETAILS_SETTINGS` 并给文字指引。
- 用 `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`（跳设置页，无需权限）而不是 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`（直接请求，Play 政策限制）。
- 完成状态存 DataStore + 上报服务端（`rider_device.keepalive_guide_done`），上班检查清单里校验。
- 提供「重新检测」按钮，`isIgnoringBatteryOptimizations()` 可以程序化检测电池优化，其余项只能靠骑手自述。

**第四层 · 可观测**

服务端 `LivenessMonitor` 发现在岗骑手超过 120 秒无位置上报，就在调度台标红并给骑手推一条催报消息。**国产 ROM 上不可能做到 100% 存活，那就让失败可见。**

### 3.3 离线写队列

生鲜配送场景里，骑手大量时间在地下车库、电梯、楼道。**「送达」这个动作必须在断网时也能完成，且记录的时间必须是骑手按下按钮的时刻，不是网络恢复的时刻。**

Room 表 `pending_action`：

```kotlin
@Entity(tableName = "pending_action")
data class PendingActionEntity(
    @PrimaryKey val clientEventId: String,   // UUID，服务端幂等键
    val actionType: String,                  // ACCEPT/PICKUP/DEPART/ARRIVE/DELIVER/EXCEPTION/...
    val taskId: Long?,
    val waveId: Long?,
    val payloadJson: String,
    val clientEventAt: Long,                 // 骑手真实操作时刻
    val lat: Double?, val lng: Double?,
    val attemptCount: Int = 0,
    val lastError: String? = null,
    val createdAt: Long,
)
```

流程：
1. 骑手操作 → **先写 Room，立即更新本地 UI 为乐观状态**，让骑手能继续下一单
2. `ActionSyncWorker`（WorkManager，网络约束 + 周期 15 秒）读队列按 `createdAt` 顺序重放
3. 成功 → 删除；失败 → `attemptCount + 1`，指数退避
4. `attemptCount > 10` → 在 UI 上标记「同步异常」，允许骑手手动重试

**顺序性很重要**：`ARRIVE` 必须在 `DELIVER` 之前到达服务端。队列严格 FIFO，失败时**阻塞后续同一任务的动作**（不同任务之间可并行）。

照片走独立队列（体积大、可乱序），先压缩到 1280 px 长边、JPEG 质量 80，再排队上传。

### 3.4 派单提醒（要像外卖 App 那样响）

**通知渠道**（Application 启动时创建，API 26+）：

| 渠道 | 重要性 | 声音 | 用途 |
| --- | --- | --- | --- |
| `channel_new_task` | `IMPORTANCE_HIGH` | 自定义 `res/raw/new_task.mp3` | 新派单 |
| `channel_urgent` | `IMPORTANCE_HIGH` | 自定义警报音 | 超时预警、强制下班 |
| `channel_normal` | `IMPORTANCE_DEFAULT` | 默认 | 普通消息 |
| `channel_service` | `IMPORTANCE_LOW` | 无 | 定位前台服务常驻通知 |

**厂商通道注意事项**（这些坑必须提前知道）：
- 自定义声音必须**在创建渠道时设置**，之后改不了。改声音需要新建渠道 id。
- 华为：数据处理地在中国时 `channel_id` 会被忽略，必须用 `options.third_party_channel.huawei.sound`，且需要**服务与通讯**消息分类审批。
- vivo：`channel_id` 需要在 vivo 后台报备审批。
- 小米：区分私信/公信消息，声音在渠道创建时设置。
- 全部厂商都要申请**服务与通讯 / 系统消息**分类。**这些审批要在项目启动时就提交，是长周期项。**

**循环语音播报**（真正让骑手不错过单的机制）：

推送只负责唤醒。App 进程活着时（上班期间前台服务保证了这一点），收到新单：
1. 播放自定义提示音
2. TTS 播报「您有 1 个新订单，阳光小区，2.1 公里」
3. **每 10 秒重复一次，最多 6 次或直到骑手在 App 内确认**
4. 同时全屏 Intent 拉起任务确认页（`FLAG_ACTIVITY_NEW_TASK` + 锁屏显示）

**轮询兜底**：上班期间每 3 秒调一次 `GET /api/rider/sync`。这个接口极轻量，只返回版本号和计数。推送失败时它保证不丢单。省电考虑：只在上班期间轮询，下班停止。

### 3.5 UI 设计原则

骑手在骑车、戴手套、单手、颠簸、强光下操作。设计约束和普通 App 完全不同。

| 原则 | 落地 |
| --- | --- |
| **滑动确认，不用点击** | 所有状态流转（接单/到店/取货/送达）都用 `SlideToConfirm` 组件，需要 ≥ 3 cm 水平位移。这是美团/蜂鸟/顺丰/闪送**四家独立收敛到的同一个方案**，因为口袋、手套、颠簸产生的误触太多了 |
| **拇指热区** | 主操作按钮固定在屏幕下方 40% 区域 |
| **超大点击目标** | 主按钮高度 ≥ 64 dp（约 12 mm），次要按钮 ≥ 48 dp |
| **一屏一件事** | 任务详情页只有一个主行动按钮，永远是「下一步该做什么」 |
| **强制阅读** | 送达按钮在关键信息（地址、楼层、件数、重量）渲染完成前保持禁用 0.8 秒 |
| **双反馈** | 每次确认都有震动 + 音效，不看屏幕也知道成功了 |
| **两个距离** | 任务卡片同时显示「距我 850 m」和「本单里程 2.1 km」，骑手决策时两个都要看 |
| **色彩语义** | 绿=确认/正常，橙=风险，红=超时/紧急。同时用图标和文字，不只靠颜色 |
| **深色模式 + 常亮** | 骑行模式下屏幕常亮（`FLAG_KEEP_SCREEN_ON`）+ 自动切换高对比度 |
| **大字号** | 支持系统字号缩放到 1.3 倍不破版 |

**任务卡片信息层级**（从上到下）：

```
┌──────────────────────────────────────┐
│ ③/5  阳光小区 3号楼 2单元          ← 序号 + 地址，最大字号
│      12 楼 1201 室                    ← 楼层单独一行，骑手最关心
├──────────────────────────────────────┤
│ 🧊 冷冻 · 优先送达      [8件 6.5kg]  ← 冷链标签高亮
│ 王女士  138****5678        [📞 拨号]  │
│ 备注：放门口，不用敲门          ⚠️有狗 │ ← 备注置顶高亮
├──────────────────────────────────────┤
│ 距我 850m · 本单 2.1km               │
│ 15:42 前送达      剩余 22:00  🟢     ← 倒计时 + 风险色
├──────────────────────────────────────┤
│  ◄◄◄  滑动确认已送达  ►►►            ← 滑动条，占满宽度
└──────────────────────────────────────┘
```

### 3.6 地图页

- 高德 `MapView` 用 `AndroidView` 包装，**必须手动转发 `onCreate/onResume/onPause/onDestroy/onSaveInstanceState`**，这是最常见的内存泄漏源。
- 图层：门店（房子图标）、待送点（编号气泡，按 `seqNo`）、已送点（灰色对勾）、骑手自身（方向箭头）、规划路线（`polyline`）。
- 一键导航：`AmapNaviPage` 唤起骑行/电动车导航到下一站；也支持跳出到高德地图 App。
- 顺序调整：长按拖拽列表重排，提交 `PUT /waves/{id}/sequence`。系统不会推翻骑手的调整，只重算 ETA。
- 地图不是主界面。骑手 90% 时间在任务列表页，地图是辅助。

### 3.7 疲劳管控 UI

| 阈值 | 行为 |
| --- | --- |
| 连续 4 小时 | 非模态提示条 + TTS 播报「已连续接单 4 小时，建议休息」；系统 20 分钟内不派单，倒计时在顶部可见 |
| 累计 8 小时 | **模态弹窗**，两个选项：「休息一下」（进入休息态）/「继续接单」（记录 `fatigue_8h_confirmed_at`，合规留证）。弹窗内提供「休息」按钮而不是只能确认继续 |
| 累计 12 小时 | 强制下班，不可跳过，未完成任务触发自动改派 |
| 连续出勤 7/26/52 天 | 提示条，并同步给店长 |

这些不是可选功能，是 GB/T 46862-2025 的明确条款。

### 3.8 网络层

```kotlin
// 拦截器链
AuthInterceptor        // 注入 Authorization: Bearer rider_xxx
  → TokenRefreshAuthenticator  // 401 时用 refreshToken 换新令牌，失败则跳登录
  → DeviceInfoInterceptor      // 注入 X-Device-Id / X-App-Version
  → RetryInterceptor           // 幂等请求（GET + 带 clientEventId 的 POST）重试 2 次
  → HttpLoggingInterceptor     // 仅 debug
```

`ApiResponse` 解包：`code != 0` 时抛 `BusinessException(code, message)`，UI 层统一映射成中文提示。特殊处理 `1003`（未上班）→ 跳转上班页，`1004`（未同意定位）→ 跳转同意页。

BaseUrl 从 BuildConfig 读，`debug` 指向开发机、`release` 指向 `https://hqhjxt.vip`。

### 3.9 本地缓存策略

| 数据 | 策略 |
| --- | --- |
| 任务列表/详情 | Room 缓存，**离线可读**。进入页面先出缓存再刷新（stale-while-revalidate） |
| 波次路线 | Room 缓存，含 polyline |
| 位置点 | Room 缓冲，上传成功后保留 1 小时再清理（便于排查） |
| 待同步动作 | Room，永不自动删除，只有成功才删 |
| 配置项 | DataStore，登录时拉取 |
| 图片 | Coil 磁盘缓存 |

**离线可用范围**：查看任务、导航、拍照、状态流转、异常上报**全部可离线完成**。不可离线的只有：登录、拉新单、查收入。

## 4. 页面清单

| 页面 | 路由 | 说明 |
| --- | --- | --- |
| 启动页 | `splash` | 令牌校验、版本检查 |
| 登录 | `login` | 手机号 + 密码 |
| 强制改密 | `change_password` | 首次登录 |
| 定位授权同意 | `location_consent` | **独立页面，PIPL 单独同意** |
| 权限引导 | `permission_guide` | 三步权限申请 |
| 保活向导 | `keepalive_guide` | 按厂商分支 |
| 首页/任务列表 | `home` | 三分区 + 上下班开关 + 疲劳条 |
| 波次详情 | `wave/{id}` | 站点顺序、总览、拖拽调整 |
| 任务详情 | `task/{id}` | 完整信息 + 主行动按钮 |
| 取货核对 | `pickup/{waveId}` | 按单勾选、件数核对、扫码 |
| 送达确认 | `deliver/{taskId}` | 核销码/拍照/滑动确认 |
| 异常上报 | `exception/{taskId}` | 类型选择 + 拍照 + 描述 |
| 拍照 | `camera` | CameraX，自动水印 |
| 地图 | `map` | 全局地图 |
| 收入 | `earning` | 今日/本周/本月 + 明细 |
| 消息中心 | `message` | —— |
| 个人中心 | `profile` | 资料、装备、健康证 |
| 设置 | `settings` | 语音开关、字号、深色模式、保活向导入口 |
| 关于 | `about` | 版本、**APP 备案号（必须可点击跳转备案系统）**、隐私政策 |

> **已从导航图移除的三页：结算详情（`settlement/{id}`）、服务分（`score`）、申诉（`appeal/{type}/{id}`）。**
>
> 家庭自营配送不给自己计价结算，也就没有评分和申诉对象。这三页原本只是进不去的占位壳，留着既误导也没用，因此连页面带路由一起删了 —— **App 里现在没有任何入口可以走到它们**。
>
> 后端的 `rider_score_event`、`rider_appeal`、结算相关接口和表都还在（见 04 文档 §1.6、§2.6），以后真要开这三块功能时，**连页面带路由一起重新加**，别只恢复一个空壳。

> **收入页保留但只做「今日统计」**：`TodayStatsScreen` 只回答"今天干了多少活"（在线时长、单量、准时、里程），不做收入明细、结算单、服务分、申诉。

## 5. 测试要求

| 层 | 内容 |
| --- | --- |
| 单元测试 | `LocationSampler` 采样间隔切换；`TrackCleaner` 异常点剔除；离线队列 FIFO 与重试退避；ETA 倒计时格式化；收入计算展示 |
| Room 测试 | 迁移、`pending_action` 顺序性、幂等键唯一约束 |
| 网络测试 | MockWebServer 覆盖 `ApiResponse` 解包、401 刷新、业务错误码映射 |
| Compose UI 测试 | `SlideToConfirm` 需要足够位移才触发；任务卡片关键信息可见；疲劳弹窗不可跳过 |
| 手工测试矩阵 | 小米/华为/OPPO/vivo 各一台真机，跑「上班→锁屏 30 分钟→检查定位是否连续」 |

**真机保活测试是不可替代的**，模拟器上一切正常不代表任何东西。

## 6. 分发

- 不上 Google Play（国内无意义）。
- **必须先完成**：软件著作权登记 + APP 备案。**应用名称、软著名称、备案名称三者必须完全一致**，所以要先定名，定完不能改。
- 内部分发用蒲公英内测模式（免费，每版本每天 500 次下载，需实名认证）。
- 备用：自建 `备案` 域名直接放 APK。骑手需要开启「安装未知来源应用」（Android 8 起是按安装来源 App 授权的，不是全局开关）。
- 关于页必须**显著展示备案号并可点击跳转**至工信部备案系统，否则可处 5000–50000 元罚款。

## 7. 已知风险

| 风险 | 缓解 |
| --- | --- |
| 国产 ROM 杀后台 | 四层保活 + 服务端存活监测 + **强烈建议统一采购机型**（能省掉大量适配工作） |
| 厂商推送分类审批周期长 | 项目启动即提交，不要等到开发完成 |
| APP 备案 20 工作日 | 与开发并行启动 |
| 高德导航 SDK 11.2 起放弃 32 位 | 确认骑手机型全为 arm64（2026 年基本都是） |
| 高德 SDK 合规调用顺序 | 必须在实例化任何 SDK 对象**之前**调用 `updatePrivacyShow` / `updatePrivacyAgree`，否则直接失败。写进 Application 初始化的第一行 |
| TTS 在部分 ROM 缺失中文引擎 | 检测失败时回退到预录音频片段 |
