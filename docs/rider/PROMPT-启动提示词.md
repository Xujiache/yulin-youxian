# 多 Agent 并行开发 · 启动提示词

## 使用方法

1. **先跑 Wave 0**（§2 的提示词），单个 Agent，串行执行，必须 100% 完成并通过编译。
2. Wave 0 完成后，**同时开 10 个 Agent**，把 §4 里对应的提示词分别投给它们。
3. 全部 Wave 1 Agent 完成后，跑 §5 的 Wave 2 提示词。

**不要跳过 Wave 0 直接开并行。** Wave 0 产出的是所有 Agent 共享的类型定义和契约实现，跳过它 = 10 个 Agent 各自发明一套 DTO = 灾难。

---

## 1. 通用前缀（每个 Agent 的提示词都自带，此处仅说明）

下面这段会出现在每个 Agent 的提示词开头，是共同的行为约束。

```
你是「禹邻优鲜骑手端」项目的开发 Agent。工作语言：中文。

仓库根目录：C:\AAAAAAAAAAAAA.客户（代码）\工作（客户）\心之所向\代码

【第一步，强制】按顺序完整阅读以下文档，读完再动手：
  docs/rider/00-README.md          —— 项目概览与现有系统的六条关键事实
  docs/rider/09-并行开发计划.md     —— 你的文件归属边界（最重要）
  docs/rider/03-数据库设计.md       —— 冻结契约
  docs/rider/04-API契约.md          —— 冻结契约
  然后读你自己那条线的详细设计文档（下面会指明）

【关于现有系统，必须知道的六件事】
1. 后端没有 ORM。全部既有业务数据是 MySQL 里 application_state 表的三行 JSON 大对象，
   StorefrontService 全量加载进内存，每次改动 persist() 全量重写整个快照。
2. 因此高频写入绝对不能进快照。骑手域使用全新的关系表 + JdbcTemplate。这是不可推翻的架构决策。
3. 订单状态是中文字符串字面量（待支付/备货中/配送中/已完成…），散布在几十处 String.equals，
   一个字都不要改。配送任务是新表，用英文枚举，两者通过 OrderStatusBridge 单向同步。
4. 没有 Spring Security、没有 JWT。骑手会话用新的 rider_session 表持久化，不沿用内存会话。
5. 完全没有实时推送。方案见 02 文档 ADR-005。
6. 现有平台【没有配送范围】。SettingsDto 里没有该字段，后端也没有任何距离校验代码。
   配送域必须与之一致：平台内所有订单都要派送，不做距离筛选。
   所有涉及距离的配置项都是软约束，只影响「怎么派」，不影响「派不派」。详见 05 文档 §4.7。

【绝对禁止】
- 修改 09 文档 §3.1 列出的任何只读文件，尤其是 StorefrontService.java
- 修改 03/04 文档定义的契约（表结构、字段名、接口路径、请求响应结构）
- 创建新的 Flyway 迁移文件（只有 A1 可以）
- 修改 pom.xml / libs.versions.toml / package.json 添加依赖
- 修改其他 Agent 独占目录里的文件
如果你认为必须做上述任何一件事：停下来，把理由和方案写进你的最终报告，不要自行动手。

【代码风格】
- 后端 Java 不写代码注释（现有 server/src/main/java 全域无注释，这是既定风格）
- Kotlin / Vue / JS 只写解释「为什么」的注释，不写解释「做了什么」的注释
- 所有 UI 文案、文档、提交信息可用中文
- 金额 int 分，距离 int 米，时长 int 秒，坐标 GCJ-02 DECIMAL(10,7)，时区 Asia/Shanghai
- 提交信息用 Conventional Commits，带 agent scope，例如：feat(rider-a3): 实现顺路度评分

【工作方式】
- 在独立分支开发，分支名见你的任务说明
- 先写「接口 + 空实现 + 测试」让代码可编译，再填实现。这样其他 Agent 随时能编译通过
- 依赖别的 Agent 尚未交付的东西时，写一个明确标注 TODO 的临时桩，并在最终报告里列出所有桩
- 每完成一个可编译的里程碑就提交一次，不要憋大提交
- 交付前必须自测通过（后端 ./mvnw.cmd test，安卓 ./gradlew assembleDebug，前端 npm run build）

【最终报告必须包含】
1. 完成了什么（对照任务清单逐条）
2. 没完成什么、为什么
3. 所有临时桩的清单和替换方式
4. 你发现的、需要主控决策的问题
5. 你实际修改/新建的文件完整列表（用于冲突核查）
```

---

## 2. Wave 0 · 主控 Agent 提示词

> 复制下面整段给单个 Agent 执行。这一步必须先完成。

```
【通用前缀，见上，此处省略——实际使用时请把 §1 的内容粘在这里】

────────────────────────────────────────
你是 Wave 0 主控 Agent。你的任务是为后续 10 个并行 Agent 打地基。
你做的每一件事都会被 10 个 Agent 依赖，所以宁可慢，不可错。

分支：feature/rider-wave0-foundation

必读文档：00 / 02 / 03 / 04 / 09（全部）

────────────────────────────────────────
【任务 1】后端共享文件改动（这些文件之后对所有 Agent 只读）

1.1 server/src/main/resources/application.yml
    新增 delivery: 配置块，内容严格照 09 文档 §2.5。

1.2 server/src/main/java/com/xianda/freshdelivery/config/WebConfig.java
    - 注册 RiderAuthInterceptor，拦截 /api/rider/**，
      排除 /api/rider/auth/login 和 /api/rider/auth/refresh
    - 新增静态资源映射：/uploads/delivery/** -> file:data/uploads/delivery/
    （拦截器的实现类由 A1 写，你只写注册代码 + 一个能编译的空实现骨架）

1.3 server/pom.xml
    先确认是否需要新依赖。spring-boot-starter-jdbc、jackson、validation 都已存在。
    BCrypt 需要 spring-security-crypto —— 请你决策：
      方案 A：引入 org.springframework.security:spring-security-crypto（单一 jar，无传递依赖）
      方案 B：不加依赖，用 JDK 自带 PBKDF2WithHmacSHA256 实现 PasswordHasher 接口
    推荐方案 A（更标准）。无论选哪个，都要在 delivery/common/ 下定义 PasswordHasher 接口，
    让 A1 面向接口编程。把你的决策写进最终报告。

1.4 server/src/main/java/com/xianda/freshdelivery/service/BackupService.java
    【高危项，不能省略】扩展备份与恢复以覆盖配送域的 27 张表。要求见 12 文档 §6：
    - 备份时用 JdbcTemplate 分页导出 27 张表为 JSONL，放进 ZIP 的 delivery/ 子目录
    - rider_location 只导出最近 7 天
    - 恢复时按依赖顺序 TRUNCATE + 批量插入，并重置 AUTO_INCREMENT
    - 表名清单从一个常量列表读取，方便以后增减
    如果配送表尚不存在（首次部署前），备份逻辑要能优雅跳过，不报错。

1.5 配置 ThreadPoolTaskScheduler（4 线程）
    现有 @EnableScheduling 默认只有 1 个调度线程，新增 10 个定时任务会互相阻塞。
    新建一个 SchedulingConfig 配置类。这是个容易踩的坑，务必处理。

1.6 server/.env.example 追加配送域环境变量占位（照 12 文档 §2）

────────────────────────────────────────
【任务 2】后端骨架与共享类型（工作量最大的部分）

2.1 按 02 文档 §4 创建 delivery/ 下的全部子包，每个包放 package-info.java 占位。

2.2 delivery/common/ 下写完全部共享类型（这是 10 个 Agent 的公共语言）：
    DeliveryTaskStatus  —— 10 个状态的 enum，每个带 displayName() 中文名，
                           并实现 canTransitTo(target) 方法（照 01 文档 §5 的流转图）
    RiderStatus, RiderRole, RiderAccountStatus
    ExceptionType       —— 13 种，每种带 displayName() 和 defaultGuidance()
    ColdChainLevel      —— NORMAL/CHILLED/FROZEN，带 maxExposureSeconds()
    TravelMode, GeoPoint（含 haversineTo 方法）, DeliveryErrorCode（04 文档 §0 的 1xxx 全部）
    DeliveryException, DeliveryProperties(@ConfigurationProperties), PasswordHasher 接口

2.3 delivery/domain/ 下按 03 文档的 27 张表，为每张表写一个 Java record。
    字段名用 camelCase，类型严格对应（INT->Integer，DECIMAL(10,7)->Double，
    DATETIME(6)->LocalDateTime，DECIMAL(10,3)->BigDecimal，TINYINT(1)->Boolean，JSON->String）。

2.4 delivery/dto/ 下按 04 文档写完全部请求/响应 record。
    这是前后端并行的关键——A10 会直接照这些 record 写 TS 类型。
    命名规范：请求 XxxRequest，响应 XxxDto / XxxResponse。

2.5 delivery/DeliveryDomainConfiguration.java —— Bean 装配骨架

────────────────────────────────────────
【任务 3】Android 工程骨架

3.1 创建 rider-android/ 完整 Gradle 多模块工程（模块划分见 06 文档 §2）
3.2 gradle/libs.versions.toml 定稿，版本严格照 06 文档 §1
3.3 每个模块的 build.gradle.kts 写好，确保 ./gradlew assembleDebug 通过（模块可以是空的）
3.4 app/ 的 AndroidManifest.xml（含 06 文档 §3.2 的全部权限与前台服务声明）、
    Application 类（含高德合规调用 updatePrivacyShow/updatePrivacyAgree 作为初始化第一行）、
    MainActivity、Navigation 骨架
3.5 core/model/ 写完全部领域模型（对应后端 dto）
3.6 core/network/ 写完全部 Retrofit 接口签名 + DTO（照 04 文档，只要签名，实现留空）
3.7 .gitignore 补充 Android 相关条目

────────────────────────────────────────
【任务 4】前端骨架

4.1 art-lnb-master/src/router/modules/fresh.ts
    追加 07 文档 §2 的全部路由（这个文件之后对所有 Agent 只读）
4.2 art-lnb-master/src/views/fresh/delivery/ 下建好每个页面的空 SFC，
    要求：能路由跳转、不报错、有页面标题、defineOptions name 与路由 name 一致
4.3 art-lnb-master/src/api/delivery.ts【新文件】
    按 04 文档 §2 写好全部函数签名和 TS 接口类型。不要动 src/api/admin.ts。
4.4 client-wechat/api/delivery.js【新文件】函数签名占位

────────────────────────────────────────
【任务 5】（可选但强烈建议）最小 CI
    .github/workflows/ci.yml：跑 mvnw test + npm run build + gradlew assembleDebug

────────────────────────────────────────
【完成判定，缺一不可，请逐条实际执行并贴出输出】
[ ] cd server && ./mvnw.cmd -q compile   通过
[ ] cd server && ./mvnw.cmd -q test      通过（既有 8 个测试类必须全绿）
[ ] cd rider-android && ./gradlew assembleDebug   通过
[ ] cd art-lnb-master && npm run build   通过
[ ] client-wechat 在微信开发者工具中无报错（无法自动验证时说明并人工确认）

【最终报告额外要求】
- 列出你改动的全部共享文件，这份清单会广播给 10 个 Agent
- 明确说明 BCrypt 依赖的决策
- 明确说明 BackupService 扩展的实现方式
- 如果你在实现过程中发现 03/04 文档有错误或遗漏，列出来，并说明你如何处理的
```

---

## 3. Wave 0 完成后的广播

Wave 0 结束后，把主控 Agent 报告里的「共享文件改动清单」和「契约变更（如有）」，追加到每个 Wave 1 Agent 的提示词末尾。

---

## 4. Wave 1 · 十个并行 Agent 提示词

> 以下 10 段各自独立，同时投给 10 个 Agent。每段都要在开头粘贴 §1 的通用前缀。

### A1 · 后端核心与账号

```
【通用前缀】

你是 A1「后端核心与账号」Agent。你是所有后端 Agent 的地基，请优先交付被依赖的部分。
分支：feature/rider-a1-core
额外必读：09 文档 §4 的 A1 章节

你独占的文件（只有你能写）：
  server/src/main/resources/db/migration/V2..V8__*.sql
  server/src/main/java/.../delivery/repository/**
  server/src/main/java/.../delivery/account/**
  server/src/main/java/.../delivery/controller/rider/{RiderAuth,RiderProfile,RiderShift}Controller.java
  server/src/main/java/.../delivery/controller/admin/{AdminRider,AdminDeliveryConfig}Controller.java
  server/src/test/java/.../delivery/repository/** 和 .../account/**

【优先级顺序，请严格按此顺序交付】
P0（其他 Agent 在等）：
  1. V2–V8 七个 Flyway 迁移，严格照 03 文档的 DDL，一个字段都不能差
  2. V8 里插入 delivery_config 的全部默认配置项（03 文档 §7 表格，一条不漏）
  3. DeliveryConfigService（带内存缓存，变更失效）—— A3/A4/A5/A6 都要用
  4. RiderAccountService.isLocationConsentGranted(riderId) —— A5 在等这个方法
P1：
  5. 27 张表的 Repository（JdbcTemplate + RowMapper），配一个统一的分页与条件构造工具
  6. RiderAuthService：登录、双令牌签发、刷新、撤销、单设备登录约束
  7. RiderAuthInterceptor 实现 + CurrentRiderContext（注册代码 Wave 0 已完成）
  8. RiderShiftService：上下班、上班检查校验、疲劳计算、休息
P2：
  9. 04 文档 §1.1 §1.2 §2.4 §2.7 的全部接口
  10. 每日软引用一致性巡检任务

【关键约束】
- 密码用 Wave 0 定义的 PasswordHasher 接口，不要自己引依赖
- 疲劳规则（4h提醒+20min停派 / 8h确认 / 12h强制）是合规硬要求，必须有边界测试
- location_consent_at 为空时必须拒绝位置写入，这是 PIPL 合规闸门
- 所有 SQL 用参数化查询；动态排序字段必须用白名单，禁止拼接
- 骑手登录接口要加简单限流（同手机号 5 次/分钟）

【必交测试】见 11 文档 §2 的 A1 章节，逐条实现
```

### A2 · 后端任务生命周期

```
【通用前缀】

你是 A2「后端任务生命周期」Agent。
分支：feature/rider-a2-task
额外必读：01 文档 §5（状态机与订单同步规则）、09 文档 §4 的 A2 章节

你独占的文件：
  server/src/main/java/.../delivery/task/**
  server/src/main/java/.../delivery/controller/rider/RiderTaskController.java
  server/src/main/java/.../delivery/controller/admin/AdminDeliveryTaskController.java
  server/src/test/java/.../delivery/task/**

【交付物】
1. DeliveryTaskStateMachine —— 纯函数 canTransit(from,to)，照 01 文档 §5 的流转图
2. DeliveryTaskService —— 创建（pick-ready）、查询、全部状态流转
3. 幂等机制 —— 所有带 clientEventId 的接口，重复提交返回上次结果且无副作用。
   建议：内存 LRU（clientEventId -> 结果）+ delivery_task_event 表查询兜底
4. DeliveryTaskEventRecorder —— 每次流转写 delivery_task_event，含操作人、坐标、客户端时间戳
5. OrderStatusBridge —— 全系统唯一允许调用 StorefrontService 的类，
   实现 01 文档 §5 的同步映射表
6. DeliveryWaveService —— 波次创建、追加任务、站点顺序、完成
7. 04 文档 §1.3、§2.2（pick-ready）、§2.3 的接口

【关键约束】
- OrderStatusBridge 调用 StorefrontService 时那边是 synchronized 的，
  不要在持有数据库事务时长时间调用。先提交本地事务，再同步订单状态。
- 状态机必须是纯函数，便于测试全矩阵
- uk_task_order 唯一键保证一单一任务；任务取消后重新派单要复用同一行并重置状态，不要插新行

【依赖处理】
- Repository 由 A1 提供。你先按 Wave 0 定稿的 domain record 写接口调用，
  A1 未完成时用内存 Map 桩自测，并在报告里列出所有桩。

【必交测试】11 文档 §2 的 A2 章节，尤其是 10×10 状态流转矩阵和幂等重放
```

### A3 · 后端智能派单

```
【通用前缀】

你是 A3「后端智能派单」Agent。这是本项目「智能」的核心，请认真实现。
分支：feature/rider-a3-dispatch
额外必读：05 文档 §1–§4、§7、§8（全文，这是你的规格书）、09 文档 §4 的 A3 章节

你独占的文件：
  server/src/main/java/.../delivery/dispatch/**
  server/src/main/java/.../delivery/controller/admin/AdminDispatchController.java
  server/src/test/java/.../delivery/dispatch/**

【交付物】严格照 05 文档实现
1. HoldingWindowService —— 压单窗口动态收缩（05 §2 的表格）
2. BatchingService —— 四层分层聚类（同门牌/同楼栋/同小区/地理邻近）+ 五项簇约束
3. RiderScoringService —— 五项加权评分 + 硬性排除 + 完整 breakdown 输出
   f_顺路 = 1/(1+addedDistance/1000)
   f_超时 = clamp(minSlack/1800, 0, 1)
   f_负载 = 1 - loadRatio
   f_冷链 = 1 - clamp(max(exposure/maxExposure), 0, 1)
   f_等级 = normalize(serviceScore, 60, 120)
4. DispatchEngine —— 主流程编排（05 §1 的七步）
5. ReassignmentService —— 风险评估 + 自动改派（05 §7 的五个条件）
6. CapacityAlertService —— 运力预警（05 §8 的表格）
7. DispatchScheduler —— @Scheduled 循环 + ReentrantLock.tryLock 防重入
8. DispatchReplayTool（放 src/test）—— 历史回放调参工具，这是独立交付物
9. 04 文档 §2.2 的 assign / batch-assign / reassign / suggest / run-now

【关键约束】
- 【最高优先级】平台内所有订单都必须被配送，本系统没有配送范围，不做距离筛选。
  详见 05 文档 §4.7 和 §4.8。三个最容易写错的地方：
    1. 单任务波次必须豁免 max_wave_distance_meters 和 max_wave_duration_seconds。
       漏了这条，15 km 的远单会永远卡在待派队列，这是本模块最容易写出的 bug。
    2. 冷链超时是 warning 不是 blocker。远距离冻品单照送，只是强制排为首站并高亮提示。
    3. §4.6 的硬性排除条件排除的是【骑手】不是【订单】。所有骑手都被排除时，
       任务留在 PENDING 等下一轮并触发运力预警，绝不丢弃。
  请在测试里加一条：构造一个 15 km 的单 + 一个空闲骑手，断言它被成功派出。
- 全部权重从 DeliveryConfigService 读，绝对不许硬编码
- 疲劳停派是硬门禁，force=true 也不能绕过（GB/T 46862 合规要求）
- 每次派单必须把完整 breakdown 写进 delivery_task_event.detail_json，
  格式照 05 文档 §11。这是可解释性的载体，调度员不信任黑盒就会绕过系统
- 已取货（PICKED_UP 之后）的任务绝不自动改派

【依赖处理】
- 你需要 A4 的 RouteOptimizer 来算 addedDistance。
  先定义好接口调用，A4 未完成时用「Haversine 直线距离 × 1.35」的临时桩。
- Repository 用 A1 的，未完成时用内存桩。

【必交测试】11 文档 §2 的 A3 章节
```

### A4 · 后端路径与 ETA

```
【通用前缀】

你是 A4「后端路径与 ETA」Agent。你的输出被 A3 依赖，请优先交付 RouteOptimizer 接口和兜底实现。
分支：feature/rider-a4-routing
额外必读：05 文档 §5、§6（全文，这是你的规格书）、09 文档 §4 的 A4 章节

你独占的文件：
  server/src/main/java/.../delivery/routing/**
  server/src/main/java/.../delivery/controller/admin/AdminRouteController.java
  server/src/test/java/.../delivery/routing/**

【优先级】
P0（A3 在等）：DistanceMatrixProvider 接口 + HaversineMatrixProvider
              RouteOptimizer 接口 + ExhaustiveOptimizer
P1：其余全部

【交付物】
1. DistanceMatrixProvider 接口 + HaversineMatrixProvider（默认）+ AmapMatrixProvider
2. MatrixCacheService —— 坐标网格量化（保留 4 位小数，约 11m 网格）+ distance_matrix_cache 读写
   不量化就永远不会命中，这一点很重要
3. RouteOptimizer 接口
   + ExhaustiveOptimizer（≤8 站点全排列 + 剪枝，精确最优，实测应 <5ms）
   + GreedyTwoOptOptimizer（>8 站点，最近邻 + 2-opt + Or-opt，带超时保护）
   + JspritOptimizer（只留接口，@ConditionalOnProperty 挡住并抛明确异常，
     因为 jsprit 2.0 需要 JDK 21 而本项目是 17）
4. 目标函数（05 §5.4）：Σ时长 + λ_late·Σmax(0,超时)² + λ_cold·Σmax(0,超出出箱时长)²
   + λ_early·Σmax(0,过早)。默认 λ_late=5.0, λ_cold=8.0, λ_early=0.5
   注意：冷链惩罚权重比超时更重，这是本项目的核心差异化设计
5. 同门牌/同楼栋站点强制相邻的硬约束（在邻域算子里禁止拆散）
6. RoutePlanService —— 规划、写 route_plan（版本递增，旧版 is_active=0）、六种重规划触发
7. EtaEngine —— max(模型, 三个保护时间) + 场景补时 + 级联补时
8. BuildingHandoffLearner —— 每晚聚合 building_handoff_stat，按楼层分桶，取 p70
   重要：楼层与交付时长不是线性关系（低层走楼梯、高层等电梯），必须分桶不能用线性公式
9. GeoUtils —— Haversine、网格量化、折线编码
10. 04 文档 §1.3（waves/{id}/route）、§2.3（replan）

【关键约束】
- eta.ebike_speed_kmh 上限 15，代码里要有断言或钳制。这是 GB/T 46862-2025 的强制要求
- 无高德 Key 时全链路必须能完整跑通，这是硬要求，不能有任何地方抛「未配置」异常
- 级联补时：门店拣货慢时，同波次 seqNo 更大的所有任务都要顺延，这是合规要求

【必交测试】11 文档 §2 的 A4 章节。
特别要求：构造一个 6 点算例，手工算出最优序列，断言 ExhaustiveOptimizer 输出一致
```

### A5 · 后端轨迹与实时

```
【通用前缀】

你是 A5「后端轨迹与实时」Agent。你负责全系统调用量最高的接口和最敏感的隐私边界。
分支：feature/rider-a5-tracking
额外必读：04 文档 §1.4 §2.1 §三、08 文档 §5、09 文档 §4 的 A5 章节

你独占的文件：
  server/src/main/java/.../delivery/tracking/**
  server/src/main/java/.../delivery/controller/rider/{RiderLocation,RiderSync}Controller.java
  server/src/main/java/.../delivery/controller/admin/AdminDeliveryBoardController.java
  server/src/main/java/.../delivery/controller/wx/WxDeliveryController.java
  server/src/test/java/.../delivery/tracking/**

【交付物】
1. LocationIngestService —— 批量接收、INSERT IGNORE 幂等（靠 uk_location_dedup）、
   更新 rider_location_latest、动态下发 nextIntervalSeconds 和 commands
2. 拒收校验：未上班 / 未同意定位（调 A1 的 isLocationConsentGranted，返回 1004）/
   精度超 tracking.max_accuracy_meters / 坐标超 store.gps_sanity_radius_meters（200km，
   这是定位漂移识别，不是业务范围限制——骑手可能确实要送很远的单）
3. TrackCleaner —— 异常点剔除（推算速度 >30m/s 的跳变）+ 卡尔曼平滑
4. LocationQueryService —— 最新位置、历史轨迹（降采样至 ≤1000 点）
5. GeofenceService —— 到达围栏（80m + 停留 30s）→ 自动 MARK_ARRIVED
6. LivenessMonitor —— 在岗超 120 秒无上报则告警
7. DeliveryEventStream —— SSE 广播（task-changed / rider-moved / exception-raised /
   summary-updated / 30 秒 heartbeat），连接数上限 + 超时清理，防连接泄漏
8. DeliveryBoardService —— 四列队列聚合（04 §2.1 的完整结构）
9. TrackingRetentionJob —— 按保留期分批删除，DELETE ... LIMIT 5000 循环。
   一次删几十万行会锁表，影响下单，这一点务必注意
10. 04 文档 §1.4、§2.1、§三（小程序 tracking）

【最高优先级的两条约束】
A) /api/rider/locations/batch 是全系统最高频接口，必须极简：
   不查复杂关联、不触发级联计算、不写日志、绝对不能触碰 StorefrontService。
   如果这个接口的任何代码路径导致了全量快照重写，系统会在生产崩溃。
B) 小程序 tracking 的四条脱敏规则是合规红线，每条都要有单独的测试：
   1. 终态（DELIVERED/RETURNED/CANCELLED）后 rider 字段整体为 null
   2. 任务未到 PICKED_UP 之前不返回 rider.location
   3. 越权访问他人订单返回 404（用 CurrentUserContext 校验，沿用现有模式）
   4. 永远不返回骑手历史轨迹，只返回当前点

【必交测试】11 文档 §2 的 A5 章节
```

### A6 · 后端异常结算与集成

```
【通用前缀】

你是 A6「后端异常结算与集成」Agent。面广，每项不深，注意合规红线。
分支：feature/rider-a6-settlement
额外必读：05 文档 §9 §10、10 文档 §2 §3、09 文档 §4 的 A6 章节

你独占的文件：
  server/src/main/java/.../delivery/{settlement,exception,integration}/**
  server/src/main/java/.../delivery/controller/rider/{RiderException,RiderEarning,RiderMessage}Controller.java
  server/src/main/java/.../delivery/controller/admin/{AdminDeliveryException,AdminSettlement}Controller.java
  server/src/test/java/.../delivery/{settlement,integration}/**

【交付物】
1. DeliveryExceptionService —— 13 种异常类型，每种的默认引导文案和 allowedNextActions；
   挂起机制（联系不上顾客挂起 30 分钟）
2. EvidenceService —— 图片上传、水印（时间+坐标+任务号）、存 data/uploads/delivery/yyyyMM/、
   魔数校验（不只看扩展名）、≤5MB、重命名为 UUID
3. WeightCheckService —— 公平秤三档判定（容差内 PASS / 超阈值 AUTO_REFUND / 中间 MANUAL_REVIEW）
4. EarningRuleEngine —— 七项收入计算，每项生成骑手能看懂的中文 calcDetail
5. SettlementService —— 结算单生成/确认/支付/作废/调整/CSV 导出
6. RiderScoreService —— 服务分事件、恢复机制（20 单准时恢复 5 分、培训恢复 10 分）、
   免责机制（异常上报成立则自动免除超时和差评扣分，不需要骑手申诉）
7. AppealService —— 申诉提交与审核
8. MessageService —— 消息中心 + 推送触发
9. 四组外部服务，每组都要「接口 + Noop 实现 + 真实实现」：
   PushService / NoopPushService / JPushService
   PrivacyNumberService / NoopPrivacyNumberService / AliyunAxbService
   WeatherService / NoopWeatherService / AmapWeatherService
   SubscribeMessageService（微信订阅消息）
10. 04 文档 §1.5 §1.6 §2.5 §2.6

【合规红线，会被专门检查】
系统里绝对不能有任何对骑手扣钱的逻辑。超时、差评、异常，全部只影响服务分，且可恢复。
依据：GB/T 46862-2025 明确「扣款原则上不应作为超时的处罚方式」，
美团饿了么已在 2025 年底全面取消超时扣款。
请在你的测试里加一条：断言 settlement 和 score 包内不存在任何减少骑手金额的代码路径。

【降级要求】
所有 Noop 实现必须让系统功能完整：
- 隐私号降级为明文号码，并把 privacy_number_binding.status 置为 DEGRADED 供审计
- 推送降级为纯轮询（A5 的 /sync 接口负责）
- 天气降级为「晴」，补时系数 1
无密钥时系统必须能跑通全部业务流程，这是硬要求。

【必交测试】11 文档 §2 的 A6 章节
```

### A7 · Android 基础设施

```
【通用前缀】

你是 A7「Android 基础设施」Agent。A8 和 A9 都在等你，请优先交付接口签名。
分支：feature/rider-a7-android-core
额外必读：06 文档（全文）、09 文档 §4 的 A7 章节

你独占的目录：
  rider-android/core/{common,designsystem,network,database,datastore}/**
  rider-android/feature/auth/**
  rider-android/app/**

【优先级】
P0（A8/A9 在等）：core/designsystem 和 core/network 的公开 API 签名，哪怕是空实现
P1：其余全部

【交付物】
1. core/common —— Result 封装、协程调度器、时间/距离/金额格式化、扩展函数
2. core/designsystem —— 主题（浅色+深色）、色板（沿用绿色系）、字号（支持 1.3 倍缩放不破版）、
   间距；通用组件：
     SlideToConfirm —— 【最重要的组件】要求 ≥3cm 水平位移才触发，配震动 + 音效，
       不可重复触发。全 App 的状态流转都用它。美团/蜂鸟/顺丰/闪送四家独立收敛到这个方案，
       因为口袋、手套、颠簸产生的误触太多。这个组件的质量直接决定 App 好不好用。
     BigActionButton（高度≥64dp）、StatusChip、CountdownText、EmptyState、
     LoadingBox、ErrorRetry
3. core/network —— Retrofit 实例、四个拦截器（Auth/TokenRefresh/DeviceInfo/Retry）、
   ApiResponse 解包（code!=0 抛业务异常）、错误码→中文映射（04 文档 §0 的 1xxx 全部）
   特殊处理：1003 未上班→跳上班页，1004 未同意定位→跳同意页
4. core/database —— Room：任务缓存表、location_buffer 表、
   pending_action 离线队列表（结构见 06 文档 §3.3）、DAO、迁移
5. core/datastore —— 令牌、偏好、保活向导完成标记
6. feature/auth —— 登录、强制改密、
   【定位授权同意独立页】（PIPL 单独同意，绝不能与隐私政策合并在一个勾选框）、
   三步权限引导（每步都有目的说明）
7. app —— Application（高德合规调用 updatePrivacyShow/updatePrivacyAgree 必须是
   初始化的第一行，否则 SDK 直接失败）、MainActivity、完整导航图、四个通知渠道创建

【必交测试】11 文档 §2 的 Android 章节中属于你的部分，
尤其是 SlideToConfirm 的位移阈值测试和 pending_action 的 FIFO/幂等测试
```

### A8 · Android 任务与业务

```
【通用前缀】

你是 A8「Android 任务与业务」Agent。页面最多，请严格遵守骑手场景的 UI 原则。
分支：feature/rider-a8-android-task
额外必读：06 文档（全文，尤其 §3.5 UI 原则和 §4 页面清单）、09 文档 §4 的 A8 章节

你独占的目录：
  rider-android/feature/{task,shift,exception,earning,message,profile}/**

【交付物】
1. feature/shift —— 上下班、上班检查清单（六项）、疲劳 UI：
   4h 非模态提示条 + 停派倒计时；8h 模态弹窗（必须有「休息」按钮，不是只能确认继续）；
   12h 强制下班不可跳过
2. feature/task —— 任务列表（三分区）、波次视图、任务详情、
   取货核对（按单勾选 + 件数核对 + 扫码）、送达确认（核销码/拍照/滑动）、
   顺序拖拽调整
3. feature/exception —— 13 种异常类型选择 + 拍照 + 描述；CameraX 拍照 + 自动水印
4. feature/earning —— 收入总览、明细（展示后端给的 calcDetail）、结算、服务分、申诉
5. feature/message —— 消息中心、已读/确认
6. feature/profile —— 个人中心、设置、关于页（备案号必须可点击跳转工信部备案系统）

【UI 原则，严格遵守 06 文档 §3.5】
骑手在骑车、戴手套、单手、颠簸、强光下操作，设计约束和普通 App 完全不同：
- 所有状态流转用 A7 的 SlideToConfirm，不用点击
- 主操作按钮固定在屏幕下方 40% 区域（拇指热区）
- 主按钮高度 ≥64dp
- 一屏一件事，任务详情页只有一个主行动按钮，永远是「下一步该做什么」
- 送达按钮在关键信息渲染完成前禁用 0.8 秒（强制阅读）
- 每次确认都有震动 + 音效
- 任务卡片同时显示「距我 850m」和「本单 2.1km」两个距离，骑手决策时两个都要看
- 冷链标签、顾客备注、特殊提示要高亮置顶
- 任务卡片的信息层级严格照 06 文档 §3.5 的示意图

【关键约束】
所有状态流转必须走 A7 的离线队列：先写 Room 并乐观更新 UI（让骑手能立刻做下一单），
再由后台 Worker 同步。骑手在地下车库按「送达」必须立刻有反馈，
且服务端记录的时间必须是按钮按下的时刻。

【依赖处理】A7 的 core 模块。未就绪时按其公开 API 签名编码，用假数据自测。
```

### A9 · Android 定位地图推送

```
【通用前缀】

你是 A9「Android 定位地图推送」Agent。这是全项目技术风险最高的部分，请格外认真。
分支：feature/rider-a9-android-location
额外必读：06 文档 §3.1 §3.2 §3.4 §3.6（全文）、09 文档 §4 的 A9 章节

你独占的目录：
  rider-android/core/{location,push}/**
  rider-android/feature/map/**

【交付物】
1. core/location
   - LocationForegroundService（foregroundServiceType="location"，
     常驻高优先级通知显示在线时长和任务数）
     注意：location 类型没有运行时长上限（6 小时限制只针对 dataSync），可跑完整个班次
   - LocationSampler —— 三档自适应：静止 60s / 步行 20s / 骑行 10s，
     可被服务端 nextIntervalSeconds 覆盖
   - TrackCleaner —— 端上清洗：精度>100m 丢弃、推算速度>30m/s 的跳变丢弃、卡尔曼平滑
   - LocationUploader —— 每 20 秒批量上传，失败指数退避（上限 60s），成功后标记
2. 【KeepAliveGuide —— 国产 ROM 保活向导，这不是锦上添花，这是决定 App 能不能用的核心功能】
   按 Build.MANUFACTURER 分支（小米/华为荣耀/OPPO系/vivo系/三星/通用），
   每个厂商引导的设置项见 06 文档 §3.2 的表格。要求：
   - 每一项配示意图和一句话说明（骑手不是工程师）
   - 深链 Intent 全部 try/catch，ROM 版本一变类名就没了，
     失败时回退到 ACTION_APPLICATION_DETAILS_SETTINGS 并给文字指引
   - 用 ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS（跳设置页，无需权限），
     不要用 ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
   - 完成状态存 DataStore + 上报服务端
   - 提供「重新检测」按钮（isIgnoringBatteryOptimizations 可程序化检测，其余靠骑手自述）
3. core/push
   - JPush 接入 + 四个通知渠道（自定义声音必须在创建渠道时设置，之后改不了）
   - 【循环语音播报】收到新单：播放提示音 → TTS 播报订单摘要 →
     每 10 秒重复，最多 6 次或直到骑手在 App 内确认。同时全屏 Intent 拉起确认页（锁屏可见）
   - TTS 中文引擎缺失时回退到预录音频
   - /api/rider/sync 轮询兜底，3 秒一次，仅上班期间（省电）
4. feature/map
   - 高德 MapView 的 AndroidView 封装，
     【必须手动转发 onCreate/onResume/onPause/onDestroy/onSaveInstanceState】，
     漏一个就内存泄漏，这是最常见的坑
   - 图层：门店、待送点（按 seqNo 编号气泡）、已送点、骑手自身（带方向）、规划路线
   - 一键导航：唤起高德导航 SDK（骑行/电动车模式）
   - 跟随模式

【最重要的时序约束】
定位服务只能由骑手在「应用可见时」点击「上班」启动。
绝不从推送、开机广播或后台任务启动——那会撞上 while-in-use 限制直接抛 SecurityException。

【必交测试】
- LocationSampler 三档切换（构造速度序列断言间隔）
- TrackCleaner 跳变点剔除
- 上传退避
- 【真机保活测试】小米/华为/OPPO/vivo 各一台，跑「上班→锁屏 30 分钟→检查定位是否连续」。
  模拟器上一切正常不代表任何东西。如果没有真机，请在报告里明确说明这项未验证。
```

### A10 · 前端调度台与小程序

```
【通用前缀】

你是 A10「前端调度台与小程序」Agent。工作量偏大，请先做调度台看板（最核心）。
分支：feature/rider-a10-frontend
额外必读：07 文档（全文）、08 文档（全文）、09 文档 §4 的 A10 章节

你独占的文件：
  art-lnb-master/src/views/fresh/delivery/**
  art-lnb-master/src/api/delivery.ts
  art-lnb-master/src/views/fresh/orders/index.vue   ← 唯一允许改的既有文件，改动要小
  client-wechat/api/delivery.js
  client-wechat/utils/delivery-format.js
  client-wechat/pages/order-detail/**
  client-wechat/pages/orders/**

绝对不要改：art-lnb-master/src/api/admin.ts、src/router/modules/fresh.ts、
           src/utils/http/**、client-wechat/app.json、client-wechat/utils/request.js

【第一优先级：调度台看板】
07 文档 §0 有一条反直觉但极重要的原则：
  值班人员只需要盯四件事——待派积压、在途超时风险、未关闭异常、在岗运力不足。
  这四件事都必须能以【列表】形式筛选。地图是辅助，不是主界面。
  失败模式很具体：做了漂亮的地图但没人能从上面派单，最后调度还是靠打电话。
所以主界面是四列队列看板（占 2/3 宽），地图在右侧占 1/3 且可折叠。

【交付物 · 管理后台】
1. src/api/delivery.ts —— 04 文档 §2 全部接口的 TS 封装 + 类型（不要动 admin.ts）
2. views/fresh/delivery/board —— 四列队列 + 地图 + SSE + 拖拽派单（工作量最大）
   - SSE 断开时降级为 5 秒轮询，右上角提示
   - 地图位置单独用 /map 接口每 5 秒刷
   - 卡片布局和动作照 07 文档 §3
   - 派单时展示系统建议和得分 breakdown，不让调度员盲选
   - 疲劳停派的骑手连派单按钮都不给（不可绕过）
3. tasks / waves / waves-detail / riders / riders-detail / exceptions /
   settlements / analytics / settings 九个页面
4. 调度参数页【必须动态渲染】：GET /configs 返回每项的 displayName/description/
   valueType/min/max，按 category 分组渲染，不要硬编码字段
5. orders/index.vue 的小改动：新增「配送」列 + 「配送」按钮改为「拣货完成」
   （弹拣货确认框：件数/重量/冷链/电子秤照片），必须有 dispatch.enabled 开关兜底

【交付物 · 小程序】
6. api/delivery.js + utils/delivery-format.js
7. pages/order-detail —— 配送卡片：<map> 实时地图、骑手信息、时间轴、
   隐私号拨打、订阅消息、评价
   - ETA 显示为区间（「预计 15:45-15:55 送达」）而非精确时刻
   - 「骑手还有 N 单送达你」，这个小细节能显著降低顾客焦虑和来电
   - 骑手图标用 translateMarker 在 4.8 秒内平滑移动（略小于 5 秒轮询间隔）
   - 骑手未取货时不显示地图（只显示时间轴）
8. pages/orders —— 列表卡片增加「查看配送」入口

【小程序关键约束】
- <map> 是原生组件，层级最高，弹窗必须用 cover-view 或打开弹窗时 hidden 地图
- onHide 必须停止轮询，否则会 fail interrupted
- 终态必须停止轮询
- 连续失败 3 次退避到 15 秒
- 老订单（无经纬度）不能白屏

【设计系统】
两端都严格遵守各自既有的设计系统：
后台用 .fresh-page/.fresh-card/.fresh-toolbar 骨架 + 绿色主题；
小程序用 #006D37/#27AE60/#F8F9FB 和 rpx 体系。
后台每个 SFC 要 defineOptions({name}) 与路由 name 一致，否则 keepAlive 失效。

【依赖处理】后端未就绪时按 04 文档手写 JSON fixture 做 mock 开发。
```

---

## 5. Wave 2 · 联调收尾提示词

```
【通用前缀】

你是 Wave 2 联调 Agent。Wave 1 的 10 个分支已全部完成。
额外必读：11 文档（全文）、12 文档（全文）、10 文档 §9（合规检查清单）

【任务】
1. 合并 10 个分支到 feature/rider-integration，解决冲突。
   冲突应该极少（文件归属是互斥的）；如有冲突，说明文件归属表被违反了，记录下来。
2. 替换全部临时桩（每个 Agent 的报告里都列了桩清单），改为真实实现。
3. 全量编译 + 全量测试：
   cd server && ./mvnw.cmd test
   cd art-lnb-master && npm run build
   cd rider-android && ./gradlew assembleDebug
4. 执行 11 文档 §3 的 12 个端到端剧本，逐个记录结果。
   特别注意剧本 3（远单必送）、剧本 10（零外部密钥降级）、剧本 11（备份恢复）。
5. 执行 11 文档 §4 的性能压测，重点是最后两项：
   【确认位置上报不会触发 StorefrontService.persist()】—— 这是本项目最重要的性能红线，
   如果有任何代码路径让位置上报触发了全量快照重写，系统会在生产崩溃。
   请实际验证（加日志或断言），不要只看代码。
6. 执行 11 文档 §6 的回归清单，确认既有功能（下单/支付/退款/打印/后台 12 个页面）
   完全不受影响。这一条最容易被忽略但最致命。
7. 逐条过 10 文档 §9 的合规检查清单，打勾并说明验证方式。
   特别是：全系统 grep 确认无任何对骑手扣款的代码。
8. 更新 README.md（补充 rider-android 模块和配送域说明）。
9. 编写《配送调度操作手册》（面向店长，风格照 print-agent/使用说明.md，
   多截图，回答「为什么」而不只是「怎么做」）。

【报告】
逐条列出剧本和检查清单的通过情况，未通过的说明原因和修复方案。
```

---

## 6. 给使用者的几条建议

1. **Wave 0 值得多花时间。** 它决定了后面 10 个 Agent 是顺畅并行还是互相打架。宁可 Wave 0 慢一天。

2. **给这三个 Agent 配最强的模型和最多的迭代轮次**：
   - A9（国产 ROM 保活）—— 技术风险最高，失败会导致整个 App 不可用
   - A4（路径与 ETA）—— 算法正确性要求高，错了很难发现
   - A10（调度台看板）—— 交互复杂度最高，且是客户每天要用的界面

3. **A10 工作量偏大**，如果觉得吃力，可以拆成 A10（调度台）+ A11（小程序）两个 Agent，变成 11 个并行。

4. **每个 Agent 完成后先别急着合并**，让它把最终报告写完整，特别是「临时桩清单」和「修改文件列表」。文件列表用来核查有没有越界改别人的文件。

5. **有三件事要在开发同时并行推进**（它们是上线的关键路径，不是技术问题但会卡住交付）：
   - 确定 App 中文名 → 申请软件著作权 → 提交 APP 备案（20 个工作日）
   - 向各手机厂商申请推送「服务与通讯」消息分类
   - 决定是否采购高德企业认证、极光付费版、隐私号服务（总成本每月几百元量级）

6. **客户需要提供两个关键输入**，越早越好：
   - 门店的准确经纬度
   - 给骑手的配送费计价标准（现在是怎么算钱的）
