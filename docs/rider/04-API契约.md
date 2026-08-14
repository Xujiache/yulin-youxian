# API 契约（冻结）

> **这是冻结契约。** 前后端各 Agent 并行开发时，唯一的对齐依据就是本文档。任何字段变更必须先改文档、再通知主控广播。

## 0. 通用约定

### 响应包装

**沿用现有的 `com.xianda.freshdelivery.common.ApiResponse`**，不新建：

```json
{ "code": 0, "message": "success", "data": {}, "timestamp": "2026-08-11T16:20:00+08:00" }
```

- `code = 0` 表示成功，非 0 表示业务失败，**HTTP 状态码仍是 200**（这是现有约定，必须保持一致）。
- 鉴权失败由拦截器直接写 HTTP 401 + `code: 401`。
- 列表沿用 `PageResult<T>`：`{ items, total, page, pageSize }`。

### 错误码（配送域新增，1xxx 段）

| code | 含义 |
| --- | --- |
| `1000` | 配送功能已停用（总闸 `delivery.enabled` / 环境变量 `DELIVERY_ENABLED` 为 `false`） |
| `1001` | 骑手未登录或令牌失效 |
| `1002` | 骑手账号被停用 |
| `1003` | 骑手未上班，禁止操作 |
| `1004` | 未取得定位授权同意（PIPL 闸门） |
| `1010` | 配送任务不存在 |
| `1011` | 任务状态不允许该操作 |
| `1012` | 任务不属于当前骑手 |
| `1013` | 订单状态不满足派单条件 |
| `1014` | 该订单已存在配送任务 |
| `1020` | 无可用骑手 |
| `1021` | 骑手已达并发上限 |
| `1022` | 骑手处于疲劳停派期 |
| `1030` | 路径规划失败 |
| `1031` | 地址缺少经纬度，无法规划 |
| `1040` | 位置数据被拒（精度过差 / GPS 漂移异常点 / 未上班） |
| `1050` | 隐私号服务不可用 |
| `1060` | 凭证上传失败 |

> **`1000` 的作用范围**（配送域总闸关闭时）：
>
> - HTTP 状态码是 **503**，不是 200 —— 这是 `1xxx` 段里唯一的例外，因为它表示服务整体不可用而非单次业务失败。
> - 骑手端**写操作**（非 `GET`/`HEAD`/`OPTIONS`）由 `DeliveryDisabledInterceptor` 统一拦掉；**读接口继续放行**，关闸后 App 不至于变成空白。
> - `/api/rider/auth/**` **不在拦截范围内**：关闸后骑手仍须能登录看到提示、改密码、正常登出，否则手机上会留一个退不掉的会话继续跑定位。
> - 管理端只有 `pick-ready` / `batch-pick-ready` / 手动触发调度这三处显式 `ensureEnabled()`，查看与取消在途任务、改配置均不受影响。
> - 总闸走环境变量，**改完必须重启后端**才生效。

### 鉴权

| 前缀 | 令牌 | 拦截器 | 说明 |
| --- | --- | --- | --- |
| `/api/rider/**` | `Authorization: Bearer rider_xxx` | `RiderAuthInterceptor`（新增） | 排除 `auth/login`、`auth/refresh` |
| `/api/admin/delivery/**` | `Authorization: Bearer admin_xxx` | `AdminAuthInterceptor`（复用） | 现有拦截器已覆盖 `/api/admin/**` |
| `/api/wx/delivery/**` | `Authorization: Bearer wx_xxx` | `WxAuthInterceptor`（复用） | 现有拦截器已覆盖 `/api/wx/**` |

### 通用数据类型

```jsonc
// GeoPoint —— 全系统统一，GCJ-02
{ "lat": 30.1234567, "lng": 120.7654321 }

// 时间 —— ISO-8601 本地时间字符串，无时区后缀，语义为 Asia/Shanghai
"2026-08-11T16:20:00"

// 金额 —— 整数，单位分
"amount": 380   // 表示 3.80 元
```

---

# 一、骑手端 API `/api/rider/**`

## 1.1 鉴权

### `POST /api/rider/auth/login` （免鉴权）

```jsonc
// 请求
{
  "phone": "13800138000",
  "password": "******",
  "deviceId": "android-xxxx",
  "deviceInfo": { "manufacturer": "Xiaomi", "model": "23127PN0CC",
                  "osVersion": "16", "appVersion": "1.0.0" }
}
// 响应 data
{
  "accessToken": "rider_5f3a...",
  "refreshToken": "rrf_9c21...",
  "accessExpireAt": "2026-09-10T16:20:00",
  "mustChangePassword": false,
  "locationConsentRequired": true,      // 未签署过定位同意时为 true
  "rider": { /* RiderProfile，见 1.2 */ }
}
```

### `POST /api/rider/auth/refresh` （免鉴权）

请求 `{ "refreshToken": "rrf_..." }`，响应同上（不含 `rider`）。

### `POST /api/rider/auth/logout`

无请求体，撤销当前会话。

### `POST /api/rider/auth/password`

`{ "oldPassword": "...", "newPassword": "..." }`。新密码规则：8–32 位，含字母和数字。

### `POST /api/rider/auth/location-consent`

**PIPL 单独同意，必须是独立的一次交互，不能与隐私政策合并。**

```jsonc
{ "agreed": true, "consentVersion": "v1.0", "agreedAt": "2026-08-11T08:00:00" }
```

`agreed = false` 时清空 `location_consent_at`，服务端此后拒收该骑手的位置数据。

## 1.2 个人信息与班次

### `GET /api/rider/profile`

```jsonc
{
  "id": 1, "riderNo": "QS000001", "name": "张三",
  "phone": "13800138000", "avatarUrl": "...",
  "role": "RIDER", "accountStatus": "ACTIVE", "workStatus": "ON_DUTY",
  "vehicleType": "EBIKE", "vehiclePlate": "浙A12345",
  "maxConcurrentTask": 8, "capacityWeightKg": 30.0,
  "probation": false, "serviceScore": 98, "levelCode": "L3",
  "totalTaskCount": 1520, "onTimeRate": 0.972,
  "healthCertExpireAt": "2027-03-01", "healthCertExpiringSoon": false,
  "locationConsentAt": "2026-08-01T09:00:00"
}
```

### `PUT /api/rider/profile` · `POST /api/rider/profile/avatar`

可改字段：`avatarUrl`、`vehiclePlate`。头像走 multipart，复用现有上传目录规范。

### `GET /api/rider/shift/current`

```jsonc
{
  "shiftId": 88, "onDuty": true,
  "onDutyAt": "2026-08-11T08:00:00",
  "onlineSeconds": 14400, "continuousSeconds": 14400,
  "restTotalSeconds": 0,
  "taskCount": 22, "deliveredCount": 20, "onTimeCount": 19,
  "mileageMeters": 32000, "earningAmount": 8600,
  "fatigue": {
    "level": "WARN_4H",                       // NONE/WARN_4H/CONFIRM_8H/FORCE_12H
    "message": "已连续接单 4 小时，建议休息 20 分钟",
    "dispatchPausedUntil": "2026-08-11T12:20:00",
    "needConfirm": false,
    "forceOffDuty": false
  }
}
```

### `POST /api/rider/shift/on-duty`

```jsonc
// 请求：上班前置检查结果，服务端会记录并在不满足时拒绝
{
  "deviceId": "android-xxxx",
  "checks": {
    "fineLocation": true, "backgroundLocation": true,
    "notification": true, "batteryOptimizationIgnored": true,
    "keepaliveGuideDone": true, "helmetConfirmed": true
  },
  "location": { "lat": 30.12, "lng": 120.76 }
}
// 响应
{ "shiftId": 88, "onDutyAt": "...", "warnings": ["未开启后台定位，配送中可能掉线"] }
```

服务端硬性拒绝条件：`location_consent_at` 为空（返回 `1004`）、账号非 `ACTIVE`（`1002`）、健康证过期且门店配置为强制。

### `POST /api/rider/shift/off-duty`

`{ "reason": "MANUAL" }`。存在未完成任务时返回 `1011` 并附带待办任务列表。

### `POST /api/rider/shift/rest`

`{ "action": "START" | "END" }`。休息会重置 `continuousSeconds`。

### `POST /api/rider/shift/fatigue-confirm`

8 小时弹窗骑手选择继续接单时调用，`{ "confirmed": true, "extendMinutes": 60 }`，服务端写 `fatigue_8h_confirmed_at` 留证。

### `GET /api/rider/shift/history?from=&to=`

返回班次列表。

## 1.3 任务

### `GET /api/rider/tasks?status=`

`status` 可选 `PENDING_ACCEPT` / `IN_PROGRESS` / `TODAY_DONE`（视图别名，非数据库状态）。

```jsonc
{
  "waves": [{
    "waveId": 501, "waveNo": "BC202608110012", "status": "DELIVERING",
    "taskCount": 5, "completedCount": 2,
    "planDistanceMeters": 4200, "planDurationSeconds": 1800,
    "planReturnAt": "2026-08-11T17:05:00",
    "maxColdChainLevel": "FROZEN",
    "stops": [ /* TaskCard 数组，按 seqNo 排序 */ ]
  }],
  "standaloneTasks": [ /* 不属于任何波次的任务 */ ]
}
```

**TaskCard**（骑手端任务卡片，字段已脱敏）：

```jsonc
{
  "taskId": 9001, "taskNo": "PS20260811000123", "orderNo": "XD20260811103015123",
  "status": "DELIVERING", "statusText": "配送中",
  "seqNo": 3, "totalStops": 5,
  "receiverName": "王女士",
  "receiverPhoneMasked": "138****5678",
  "callNumber": "17012345678",            // 隐私号；降级时为真实号并带 phoneDegraded=true
  "phoneDegraded": false,
  "addressDetail": "阳光小区 3 号楼 2 单元 1201 室",
  "areaLabel": "阳光小区", "buildingLabel": "3号楼",
  "unitNo": 2, "floorNo": 12, "roomNo": "1201",
  "location": { "lat": 30.1234567, "lng": 120.7654321 },
  "distanceFromRiderMeters": 850,
  "itemCount": 8, "totalWeightKg": 6.5, "packageCount": 2,
  "coldChainLevel": "FROZEN", "coldChainText": "冷冻，请优先送达",
  "goodsSummary": "西红柿 2斤、冻虾仁 1盒 等 8 件",
  "customerRemark": "放门口，不用敲门",
  "deliveryInstruction": "放门口",
  "highlightNotes": ["有狗", "电话轻声"],
  "slotLabel": "今日 14:00-16:00",
  "promisedAt": "2026-08-11T16:00:00",
  "etaAt": "2026-08-11T15:42:00",
  "remainingSeconds": 1320,
  "overtimeRisk": "LOW",                  // LOW/MEDIUM/HIGH/OVERTIME
  "requireVerifyCode": false,
  "requirePhoto": true,
  "sameAddressTaskCount": 2               // 同门牌多单提示
}
```

### `GET /api/rider/tasks/{taskId}`

返回 TaskCard + 商品明细 + 事件时间轴 + 已上传凭证。

### 状态流转（全部为幂等设计）

所有流转接口共享一组请求头/字段：

```jsonc
{
  "clientEventId": "uuid-v4",             // 幂等键，离线重放必带
  "clientEventAt": "2026-08-11T15:40:12", // 客户端原始时间戳
  "location": { "lat": 30.12, "lng": 120.76 }
}
```

服务端按 `clientEventId` 去重：重复提交返回上一次的成功结果，不报错。**这是离线队列能正确工作的前提。**

| 接口 | 前置状态 | 后置状态 | 额外字段 |
| --- | --- | --- | --- |
| `POST /api/rider/tasks/{id}/accept` | ASSIGNED | ACCEPTED | —— |
| `POST /api/rider/tasks/{id}/reject` | ASSIGNED | PENDING | `reason` |
| `POST /api/rider/waves/{id}/pickup` | ACCEPTED | PICKED_UP（整波次） | `checkedTaskIds[]`, `actualPackageCount` |
| `POST /api/rider/tasks/{id}/depart` | PICKED_UP | DELIVERING | —— |
| `POST /api/rider/tasks/{id}/arrive` | DELIVERING | ARRIVED | —— |
| `POST /api/rider/tasks/{id}/deliver` | ARRIVED / DELIVERING | DELIVERED | `verifyCode?`, `evidenceIds[]`, `receiveMethod` |
| `POST /api/rider/tasks/{id}/return` | 任意进行中 | RETURNED | `reason`, `evidenceIds[]` |
| `POST /api/rider/tasks/{id}/transfer` | **仅 ACCEPTED** | PENDING | `reason` |

> **勘误**：本表原写「ACCEPTED / PICKED_UP → PENDING」，与 01 文档 §5 的冻结状态机冲突，已修正为仅 `ACCEPTED`。
> 理由也符合现实：货已在骑手保温箱里时不能凭空转单，必须先「异常上报 → 退回门店」再重新派单。
> 从 `PICKED_UP` 调 transfer 一律抛 `1011`。`return`（任意进行中 → RETURNED）与「已取货后管理员取消」走 `EXCEPTION` 中转两跳，每跳都过状态机校验并各记一条事件。

`receiveMethod` 取值：`FACE_TO_FACE`（当面）/ `DOOR`（放门口）/ `RECEPTION`（前台）/ `LOCKER`（快递柜）。

### `PUT /api/rider/waves/{waveId}/sequence`

骑手手动调整配送顺序。`{ "taskIds": [9003, 9001, 9002] }`。服务端保留 `original_seq_no` 用于学习骑手偏好。

### `GET /api/rider/waves/{waveId}/route`

```jsonc
{
  "waveId": 501, "planVersion": 3,
  "optimizerName": "GREEDY_2OPT", "matrixProvider": "HAVERSINE",
  "origin": { "lat": 30.10, "lng": 120.70, "name": "禹邻优鲜门店" },
  "stops": [{
    "taskId": 9001, "seqNo": 1, "location": {...},
    "legDistanceMeters": 800, "legDurationSeconds": 200,
    "handoffEstimateSeconds": 180,
    "planArriveAt": "...", "planDepartAt": "..."
  }],
  "totalDistanceMeters": 4200, "totalDurationSeconds": 1800,
  "polyline": "编码折线（无高德 Key 时为直线段串联）"
}
```

## 1.4 位置上报

### `POST /api/rider/locations/batch`

**这是全系统调用量最大的接口，设计要点是批量 + 幂等 + 极简。**

```jsonc
{
  "batchKey": "uuid-v4",
  "points": [{
    "lat": 30.1234567, "lng": 120.7654321,
    "accuracyMeters": 12, "speedMps": 4.2, "bearing": 178.5, "altitude": 15.2,
    "provider": "GPS", "motionState": "RIDING",
    "batteryLevel": 68, "networkType": "5G",
    "locatedAt": "2026-08-11T15:40:12"
  }],
  "currentTaskId": 9001, "waveId": 501
}
// 响应
{
  "accepted": 12, "rejected": 1,
  "rejectReasons": [{ "index": 5, "reason": "ACCURACY_TOO_LOW" }],
  "serverTime": "2026-08-11T15:40:20",
  "nextIntervalSeconds": 10,              // 服务端可动态下发采样间隔
  "commands": [                           // 服务端搭车下发指令，省一次轮询
    { "type": "REFRESH_TASKS" },
    { "type": "MARK_ARRIVED", "taskId": 9001 }
  ]
}
```

服务端用 `INSERT IGNORE` 配合 `uk_location_dedup(rider_id, located_at)` 实现去重，重复点静默跳过。

拒收条件：骑手未上班、未签署定位同意、精度差于 `tracking.max_accuracy_meters`、坐标超出 `store.gps_sanity_radius_meters`（默认 200 km，这是**定位漂移识别**，不是业务范围限制——骑手可能确实要送很远的单）。

### `GET /api/rider/sync`

应用内轮询兜底接口，3 秒一次，用于推送失效时不丢单。

```jsonc
{
  "serverTime": "...",
  "hasNewTask": true,
  "pendingAcceptCount": 1,
  "taskVersion": 15823,                   // 变化则客户端拉全量
  "unreadMessageCount": 2,
  "urgentMessages": [ /* need_voice=true 的消息，用于语音播报 */ ],
  "fatigue": { /* 同 shift/current */ },
  "config": { "reportIntervalSeconds": 10, "syncIntervalSeconds": 3 }
}
```

## 1.5 异常与凭证

### `POST /api/rider/exceptions`

```jsonc
{
  "clientEventId": "uuid", "clientEventAt": "...",
  "taskId": 9001,
  "exceptionType": "CUSTOMER_UNREACHABLE",
  "description": "拨打 3 次无人接听",
  "evidenceIds": [301, 302],
  "location": { "lat": 30.12, "lng": 120.76 }
}
// 响应
{
  "exceptionId": 55, "exceptionNo": "YC20260811000012",
  "status": "OPEN",
  "riderExempt": true,
  "holdUntilAt": "2026-08-11T16:12:00",
  "guidance": "已为您挂起 30 分钟，期间可先配送其他订单。若顾客未联系您，可申请退回门店。",
  "allowedNextActions": ["CONTINUE", "RETURN"]
}
```

### `GET /api/rider/exceptions?status=` · `GET /api/rider/exceptions/{id}`

### `POST /api/rider/evidences` （multipart）

字段：`file`（图片）、`evidenceType`、`taskId?`、`exceptionId?`、`lat?`、`lng?`、`capturedAt?`。
服务端加水印（时间 + 坐标 + 任务号），存 `data/uploads/delivery/yyyyMM/`，返回 `{ id, fileUrl }`。
单文件限 5 MB（沿用现有 multipart 配置），客户端应先压缩到 1280 px 长边。

### `POST /api/rider/tasks/{id}/call`

请求隐私号拨打通道。

```jsonc
// 响应
{ "callNumber": "17012345678", "degraded": false, "expireAt": "2026-08-11T18:00:00" }
// 降级时
{ "callNumber": "13800138000", "degraded": true, "notice": "隐私号服务暂不可用，请勿保存顾客号码" }
```

## 1.6 收入、服务分、消息

- `GET /api/rider/earnings/summary?period=TODAY|WEEK|MONTH`
- `GET /api/rider/earnings/items?from=&to=&page=&pageSize=` —— 每条含 `calcDetail`（可读计算过程，骑手能看懂钱是怎么算的）
- `GET /api/rider/settlements?page=` · `GET /api/rider/settlements/{id}`
- `GET /api/rider/score` —— `{ score, level, levelName, nextLevelScore, recentEvents[] }`
- `GET /api/rider/score/events?page=`
- `POST /api/rider/appeals` —— `{ targetType, targetId, reason, evidenceIds[] }`
- `GET /api/rider/appeals?status=`
- `GET /api/rider/messages?unreadOnly=&page=`
- `POST /api/rider/messages/{id}/read` · `POST /api/rider/messages/{id}/ack`
- `POST /api/rider/devices` —— 上报/更新设备与推送 `registrationId`。可附带 `appVersionCode`、`managedMode`（`DEVICE_OWNER` / `PROFILE_OWNER` / `STANDARD`）、`lastUpdateStatus`、`lastUpdateVersionCode`。

## 1.7 公开版本检查（登录前可用，不受配送关闸影响）

- `GET /api/public/rider/app/latest?channel=&versionCode=` —— **不要**把 APK 元数据挂到 3 秒一次的 `GET /api/rider/sync`。
  - `policy`: `NONE` / `OPTIONAL` / `FORCE`
  - `fileUrl` 为公开不可变路径 `/uploads/apk/**`（可带站点前缀），安装器不能走 HMAC/Authorization
  - 比较 `versionCode`，不是 versionName

消息 `messageType=APP_UPDATE`、`linkType=APP`、`linkTarget=versionCode`。必须按骑手逐条发送。

---

# 二、管理后台 API `/api/admin/delivery/**`

## 2.1 调度看板

### `GET /api/admin/delivery/board`

**这是调度台的主接口，一次返回四个队列的全部数据。**

```jsonc
{
  "serverTime": "2026-08-11T15:40:00",
  "summary": {
    "pendingCount": 6, "assignedCount": 3, "deliveringCount": 12,
    "overtimeRiskCount": 2, "openExceptionCount": 1,
    "onDutyRiderCount": 4, "availableRiderCount": 1,
    "capacityWarning": true,
    "avgDeliveryMinutes": 27, "onTimeRateToday": 0.96
  },
  "queues": {
    "pending":       [ /* AdminTaskCard，待派积压 */ ],
    "overtimeRisk":  [ /* 在途超时风险，按剩余时间升序 */ ],
    "openException": [ /* 未关闭异常 */ ],
    "idleRiders":    [ /* RiderBoardCard，在岗但空闲 */ ]
  },
  "riders": [ /* RiderBoardCard，全部在岗骑手 */ ],
  "waves": [ /* 进行中波次概览 */ ]
}
```

**AdminTaskCard** 在 TaskCard 基础上增加：`receiverPhone`（真实号码，后台可见）、`riderId`、`riderName`、`dispatchScore`、`reassignCount`、`holdUntilAt`、`createdAt`、`pickedReadyAt`、`waveNo`。

**RiderBoardCard**：

```jsonc
{
  "riderId": 1, "riderNo": "QS000001", "name": "张三", "avatarUrl": "...",
  "workStatus": "ON_DUTY", "onDutySeconds": 14400,
  "location": { "lat": 30.12, "lng": 120.76 },
  "locatedAt": "2026-08-11T15:39:50",
  "locationStale": false,                  // 超过 120 秒无上报则 true，前端标红
  "batteryLevel": 68,
  "currentWaveId": 501, "currentTaskCount": 3, "maxConcurrentTask": 8,
  "loadRatio": 0.375,
  "currentWeightKg": 12.5, "capacityWeightKg": 30.0,
  "todayDeliveredCount": 20, "todayOnTimeRate": 0.95,
  "serviceScore": 98, "probation": false,
  "fatigueLevel": "WARN_4H", "dispatchPausedUntil": "2026-08-11T12:20:00",
  "planReturnAt": "2026-08-11T17:05:00"
}
```

### `GET /api/admin/delivery/board/stream` （SSE）

事件类型：`task-changed` / `rider-moved` / `exception-raised` / `summary-updated`。
每 30 秒发一次 `heartbeat`。前端断线自动重连并全量刷新一次。

### `GET /api/admin/delivery/map`

地图专用轻量接口（比 `board` 便宜，可以更高频轮询）：`{ riders: [{riderId, lat, lng, bearing, locatedAt}], tasks: [{taskId, lat, lng, status}] }`。

## 2.2 拣货与派单

### `POST /api/admin/delivery/orders/{orderId}/pick-ready`

**派单的触发点。** 后台点「拣货完成」时调用，创建配送任务。

```jsonc
{
  "itemCount": 8, "totalWeightKg": 6.5, "packageCount": 2,
  "coldChainLevel": "FROZEN",
  "weightChecks": [{ "orderItemId": 1, "productName": "西红柿",
                     "orderedQty": 2.0, "pickedWeightKg": 1.02,
                     "scaleEvidenceId": 401 }],
  "autoDispatch": true
}
// 响应
{ "taskId": 9001, "taskNo": "PS...", "status": "PENDING",
  "holdUntilAt": "2026-08-11T15:42:00",
  "dispatchPreview": { "recommendedRiderId": 1, "recommendedRiderName": "张三",
                       "score": 0.8123, "reason": "顺路度高，剩余运力充足" } }
```

### `POST /api/admin/delivery/orders/batch-pick-ready`

`{ "orderIds": [...], "autoDispatch": true }`，返回 `BatchOrderActionResult` 风格的结果（沿用现有约定：`requested/success/skipped/errors[]`）。

### `POST /api/admin/delivery/tasks/{taskId}/assign`

`{ "riderId": 1, "force": false, "reason": "手动指派" }`。`force=true` 时忽略并发上限和疲劳限制（但仍记录）。

### `POST /api/admin/delivery/tasks/batch-assign`

`{ "taskIds": [...], "riderId": 1, "createWave": true }`。

### `POST /api/admin/delivery/tasks/{taskId}/reassign`

`{ "toRiderId": 2, "reason": "原骑手车辆故障" }`。**必须写事件流水，改派留痕可回查。**

### `POST /api/admin/delivery/tasks/{taskId}/cancel`

`{ "reason": "..." }`。订单回到 `备货中`。

### `POST /api/admin/delivery/dispatch/suggest`

不落库的派单建议（调度员决策辅助）：

```jsonc
// 请求 { "taskIds": [9001, 9002] }
// 响应
{ "suggestions": [{
    "taskId": 9001,
    "candidates": [{
      "riderId": 1, "riderName": "张三", "score": 0.8123,
      "addedDistanceMeters": 320, "addedDurationSeconds": 90,
      "estimatedArriveAt": "2026-08-11T15:52:00",
      "overtimeRiskAfter": "LOW",
      "breakdown": { "addedDistance": 0.31, "overtimeRisk": 0.28,
                     "loadBalance": 0.12, "coldChain": 0.14, "riderLevel": 0.04 },
      "blockers": []                       // 如 ["FATIGUE_PAUSED"]
    }],
    "recommendedRiderId": 1,
    "batchingHint": { "mergeWithTaskIds": [9002], "reason": "同小区同楼栋" }
}]}
```

### `POST /api/admin/delivery/dispatch/run-now`

手动触发一次调度循环（调试用）。

### `GET /api/admin/delivery/tasks/{taskId}/events`（补充，前端任务详情时间轴需要）

返回该任务的 `delivery_task_event` 全量流水，按 `id` 升序，字段与表结构一致（`eventType`/`fromStatus`/`toStatus`/`operatorType`/`operatorName`/`reason`/`detailJson`/`lat`/`lng`/`clientEventAt`/`createdAt`）。改派留痕靠它回查。

## 2.3 波次与路线

- `GET /api/admin/delivery/waves?date=&status=&riderId=`
- `GET /api/admin/delivery/waves/{id}` —— 含全部站点、路线、实际轨迹
- `POST /api/admin/delivery/waves` —— 手动建波次 `{ riderId, taskIds[] }`
- `POST /api/admin/delivery/waves/{id}/replan` —— 重新规划 `{ reason }`
- `PUT /api/admin/delivery/waves/{id}/sequence` —— 手动调整顺序
- `POST /api/admin/delivery/waves/{id}/cancel`
- `GET /api/admin/delivery/waves/{id}/replay?speed=` —— 轨迹回放数据

## 2.4 骑手管理

- `GET /api/admin/delivery/riders?status=&keyword=&page=`
- `POST /api/admin/delivery/riders` —— 新建，返回初始密码（仅此一次明文返回）
- `GET /api/admin/delivery/riders/{id}` —— 含今日/本周统计
- `PUT /api/admin/delivery/riders/{id}`
- `POST /api/admin/delivery/riders/{id}/reset-password`
- `POST /api/admin/delivery/riders/{id}/suspend` · `/activate`
- `POST /api/admin/delivery/riders/{id}/force-off-duty` —— `{ reason }`，同时撤销会话
- `GET /api/admin/delivery/riders/{id}/track?date=&from=&to=` —— 历史轨迹（降采样后返回，最多 1000 点）
- `GET /api/admin/delivery/riders/{id}/shifts?from=&to=`

## 2.5 异常、凭证、公平秤

- `GET /api/admin/delivery/exceptions?status=&type=&severity=&page=`
- `GET /api/admin/delivery/exceptions/{id}`
- `POST /api/admin/delivery/exceptions/{id}/handle`
  `{ "resolutionType": "RETURN", "resolutionNote": "...", "riderExempt": true }`
- `GET /api/admin/delivery/evidences?taskId=&type=`
- `GET /api/admin/delivery/weight-checks?verdict=&page=`
- `POST /api/admin/delivery/weight-checks/{id}/judge`
  `{ "verdict": "AUTO_REFUND", "refundAmount": 380, "note": "..." }`

## 2.6 结算与服务分

- `GET /api/admin/delivery/settlements?riderId=&period=&status=&page=`
- `POST /api/admin/delivery/settlements/generate` —— `{ periodType, periodStart, periodEnd, riderIds[]? }`
- `POST /api/admin/delivery/settlements/{id}/confirm` · `/pay` · `/void`
- `PUT /api/admin/delivery/settlements/{id}/adjust` —— `{ adjustAmount, remark }`
- `GET /api/admin/delivery/settlements/{id}/export` —— CSV（沿用现有 dashboard 导出风格）
- `GET /api/admin/delivery/score-events?riderId=&page=`
- `POST /api/admin/delivery/score-events` —— 人工加减分 `{ riderId, scoreDelta, reason }`
- `GET /api/admin/delivery/appeals?status=&page=`
- `POST /api/admin/delivery/appeals/{id}/review` —— `{ approved, reviewNote }`

## 2.7 配置与区域

- `GET /api/admin/delivery/configs?category=` —— 返回带 `displayName`/`description`/`min`/`max` 的完整配置项，前端据此渲染表单，不硬编码
- `PUT /api/admin/delivery/configs` —— `{ "items": [{ "key": "...", "value": "..." }] }`
- `GET /api/admin/delivery/zones` · `POST` · `PUT /{id}` · `DELETE /{id}`
- `GET /api/admin/delivery/analytics/overview?from=&to=`
  —— 准时率、平均配送时长、人效、异常分布、楼栋难度榜、各时段单量热力
- `GET /api/admin/delivery/analytics/riders?from=&to=`
- `POST /api/admin/delivery/messages/broadcast` —— `{ title, content, riderIds[]?, priority, needVoice, messageType?, linkType?, linkTarget? }`

## 2.x 骑手 Android 版本中心 `/api/admin/rider-app/**`

- `GET /api/admin/rider-app/releases?channel=&page=` —— 分页历史
- `GET /api/admin/rider-app/coverage?channel=` —— 近 7 日设备覆盖
- `POST /api/admin/rider-app/releases` —— multipart 上传草稿（`file` + title/notes/policy）
- `POST /api/admin/rider-app/releases/{id}/publish` —— 发布并全员推送
- `POST /api/admin/rider-app/releases/{id}/notify` —— 重新推送
- `POST /api/admin/rider-app/releases/{id}/disable` —— 停用
- `POST /api/admin/rider-app/releases/{id}/activate` —— 回滚当前指针
- `POST /api/internal/rider-app/releases` —— CI 令牌幂等上传并发布（header `X-Rider-App-Publish-Token`）

已发布记录不可删除或改低 versionCode。同一 channel+versionCode+sha256 重复提交返回已有记录；hash 不同返回 409。

---

---

# 三、小程序顾客端 API `/api/wx/delivery/**`

### `GET /api/wx/delivery/orders/{orderId}/tracking`

**顾客侧最核心的接口，5 秒轮询。设计上必须极其轻量且严格脱敏。**

```jsonc
{
  "hasDelivery": true,
  "taskStatus": "DELIVERING", "taskStatusText": "骑手正在配送",
  "timeline": [
    { "code": "ASSIGNED",  "label": "已安排骑手", "at": "2026-08-11T15:20:00", "done": true },
    { "code": "PICKED_UP", "label": "骑手已取货", "at": "2026-08-11T15:30:00", "done": true },
    { "code": "DELIVERING","label": "配送中",     "at": "2026-08-11T15:31:00", "done": true },
    { "code": "ARRIVED",   "label": "即将送达",   "at": null, "done": false },
    { "code": "DELIVERED", "label": "已送达",     "at": null, "done": false }
  ],
  "rider": {
    "name": "张师傅",                     // 姓 + 师傅，不返回全名
    "avatarUrl": "...",
    "vehicleType": "EBIKE",
    "ratingStar": 4.9,
    "callNumber": "17012345678",          // 隐私号
    "location": { "lat": 30.1200000, "lng": 120.7600000 },
    "bearing": 178.5,
    "locatedAt": "2026-08-11T15:39:50",
    "locationFresh": true
  },
  "destination": { "lat": 30.1234567, "lng": 120.7654321 },
  "store": { "lat": 30.1000000, "lng": 120.7000000, "name": "禹邻优鲜" },
  "eta": {
    "displayText": "预计 15:45-15:55 送达",
    "lowerAt": "2026-08-11T15:45:00",
    "upperAt": "2026-08-11T15:55:00",
    "remainingSeconds": 900,
    "isRange": true,
    "source": "LIVE",                 // LIVE=按最新点重算；失败时 SNAPSHOT=回退 eta_at 区间
    "updatedAt": "2026-08-11T15:39:50"
  },
  "distanceMeters": 850,
  "stopsAhead": 1,                        // 骑手在你前面还有几单；定位暂时不可用时也会下发
  "recentTrail": [                        // 仅发车后返回：最近 180 秒清洗点，最多 20 个
    { "lat": 30.1195000, "lng": 120.7595000, "at": "2026-08-11T15:38:40" },
    { "lat": 30.1200000, "lng": 120.7600000, "at": "2026-08-11T15:39:50" }
  ],
  "remainingRoute": {                     // 到本单的剩余路线几何，不含前序顾客姓名/地址/marker
    "polyline": "encoded",
    "distanceMeters": 850,
    "points": [
      { "lat": 30.1200000, "lng": 120.7600000 },
      { "lat": 30.1234567, "lng": 120.7654321 }
    ]
  },
  "polling": { "intervalSeconds": 5, "stopWhenDone": true },
  "subscribeTemplateIds": ["tmpl_xxx", "tmpl_yyy"],  // 可下发的订阅消息模板；为空或缺失时小程序隐藏「送达时提醒我」按钮
  "notice": null                          // 异常时如「骑手正在联系您」
}
```

> **字段名已冻结为 `subscribeTemplateIds`**（顶层，非嵌套）。小程序侧对 `templateIds` / `subscribe.templateIds` / `notification.templateIds` 三种写法做了兼容兜底，但服务端**必须**按 `subscribeTemplateIds` 下发。

**脱敏与失效规则（强制）**：

- `rider.location`、`recentTrail`、`remainingRoute` 只在骑手**确认发车**（`departedAt` 非空 / `DELIVERING`）后返回。已取货未发车只给骑手卡片，不给坐标。
- `recentTrail` 仅含当前骑手、当前波次、发车之后、最近 180 秒（`tracking.customer_trail_seconds`）的清洗点，最多 20 个；不是完整班次历史，也不含其他顾客地址。
- `remainingRoute` 只下发到**本单**的路线几何（折线点），不下发前序订单的姓名、地址或 marker。高德 Web 服务 Key 未配置时客户端用骑手到顾客的虚线兜底。
- 动态 ETA（`eta.source=LIVE`）以骑手最新点为起点，只累加未完成站点直到本单；失败时回退 `eta_at / eta_lower_at / eta_upper_at`（`SNAPSHOT`）。
- 任务进入 `DELIVERED` / `RETURNED` / `CANCELLED` 后，`rider`、`recentTrail`、`remainingRoute` 整体置 `null`，`polling.stopWhenDone = true`。
- 只有订单归属用户本人可查，其他人返回 404（复用现有 `CurrentUserContext` 校验模式）。
- 发车后 `polling.intervalSeconds = 5`；发车前为 12 秒，便于备货中页面自动同步到配送中。

### `POST /api/wx/delivery/orders/{orderId}/subscribe`

`{ "templateIds": ["...", "..."] }`，记录顾客的一次性订阅授权，用于后续发送「已接单/已出发/即将送达」。

### `POST /api/wx/delivery/orders/{orderId}/rating`

`{ "star": 5, "tags": ["准时","礼貌"], "comment": "..." }`。

### `POST /api/wx/delivery/orders/{orderId}/instruction`

顾客补充配送指令：`{ "instruction": "放门口", "remark": "不用敲门" }`，实时同步到骑手 App。

---

# 四、与既有接口的衔接（零侵入方案）

**原则：不修改任何既有接口、既有 DTO、既有控制器、既有 Service。** 改 `AdminOrderDto` 这类既有 record 会迫使 `StorefrontService`（禁改文件）跟着改构造点，违反配送域→订单域的单向依赖。集成全部通过「新增接口 + 前端合并 + 配置开关」完成：

| 需求 | 方案 |
| --- | --- |
| 管理后台订单列表/详情展示配送信息 | 新增 `GET /api/admin/delivery/tasks/by-orders`（归 A2），前端对当前可见订单行批量查询并合并渲染 |
| 小程序判断是否展示配送地图 | **不改 `OrderDetailDto`**。订单状态为 `备货中`/`配送中`/`已完成` 时直接调 tracking 接口，`hasDelivery=false` 则回退现有静态文案 |
| 派单入口 | 既有 `POST /api/admin/orders/{id}/deliver` **保持原样**。管理后台前端读 `dispatch.enabled` 配置切换：启用时「配送」按钮改调 `pick-ready`，未启用时走老接口 |
| 订单状态同步 | `OrderStatusBridge`（A2）调用既有 Service 的公开方法完成 `备货中→配送中→已完成` 流转，不改其内部逻辑 |

### 新增：`GET /api/admin/delivery/tasks/by-orders?orderIds=1001,1002,...`（≤100 个）

```jsonc
// 响应 data；无配送任务的订单不出现在 items 里
{
  "items": {
    "1001": { "taskId": 9001, "taskNo": "PS20260811000123",
              "status": "DELIVERING", "statusText": "配送中",
              "riderId": 1, "riderName": "张三",
              "waveNo": "BC202608110012", "etaAt": "2026-08-11T15:42:00",
              "overtimeRisk": "LOW", "farDelivery": false }
  }
}
```

---

# 五、契约测试清单

每个后端 Agent 交付时必须自带以下测试，作为契约合规的证明：

1. 每个接口的成功路径响应结构与本文档一致（用 `MockMvc` + JSON 路径断言）。
2. 每个状态流转接口的**非法前置状态**返回 `1011`。
3. 每个带 `clientEventId` 的接口**重复提交返回相同结果且不产生副作用**。
4. `/api/rider/**` 无令牌访问返回 401。
5. 顾客 tracking 接口访问他人订单返回 404。
6. 任务终态后 tracking 接口的 `rider` 字段为 `null`。
7. 未签署定位同意时位置上报返回 `1004`。
