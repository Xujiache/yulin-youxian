# CONSENSUS - 打印策略配置

## 需求共识

### 明确的需求描述

**目标**：在管理后台增强打印策略配置功能，支持自动打印和手动批量打印两种模式。

**功能范围**：
1. **自动打印模式**（已实现，无需开发）
   - 通过 `autoPrintOnPaid` 配置项控制
   - 订单支付成功后自动创建打印任务
   
2. **手动批量打印模式**（新增开发）
   - 在订单管理页面增加打印状态显示
   - 支持批量选择已支付订单
   - 批量创建打印任务
   - 支持按打印状态筛选订单

---

## 技术实现方案

### 后端方案

#### 1. 数据模型增强
```java
// AdminOrderDto 增加打印状态字段
public record AdminOrderDto(
    // ... 现有字段
    String printStatus,        // NONE/PENDING/SUCCESS/FAILED
    Long printJobId            // 关联的打印任务ID（可选）
)
```

#### 2. 新增接口
```java
// 批量创建打印任务
POST /api/admin/printing/orders/batch
Request: { orderIds: [1, 2, 3] }
Response: { 
    success: 2, 
    failed: 1, 
    jobs: [PrintJobDto, ...],
    errors: [{ orderId: 3, reason: "订单未支付" }]
}
```

#### 3. 查询增强
- `GET /api/admin/orders` 增加 `printStatus` 查询参数
- 订单列表查询时关联打印任务状态

### 前端方案

#### 1. 订单列表页面改造
- 增加"打印状态"列
- 增加批量选择功能（Checkbox）
- 增加"批量打印"按钮
- 增加打印状态筛选器

#### 2. API 封装
```typescript
// admin.ts 新增接口
export function batchPrintOrders(orderIds: number[]) {
  return request.post<BatchPrintResult>({
    url: '/api/admin/printing/orders/batch',
    data: { orderIds }
  })
}
```

---

## 技术约束

### 后端约束
- ✅ 复用现有 `PrintJobService.enqueuePaidOrder` 方法
- ✅ 保持与打印代理的兼容性
- ✅ 打印状态通过关联查询实时获取，不持久化到订单表
- ✅ 遵循现有 Spring Boot 项目结构

### 前端约束
- ✅ 使用 Element Plus 组件库
- ✅ 遵循 Vue 3 Composition API 风格
- ✅ 保持与现有订单管理页面 UI 一致性
- ✅ 复用 `art-lnb-master/src/api/admin.ts` 封装

### 业务约束
- ✅ 仅已支付订单可批量打印
- ✅ 避免重复打印（已有 SUCCESS 任务的订单跳过）
- ✅ 复用现有管理员认证，无需独立权限控制

---

## 集成方案

### 与现有系统集成
1. **订单系统集成**
   - 在 `AdminOrderController` 增加打印状态查询逻辑
   - 通过 `PrintJobService` 获取订单的打印任务状态
   
2. **打印系统集成**
   - 在 `AdminPrintController` 增加批量打印接口
   - 调用 `PrintJobService.enqueuePaidOrder` 创建任务
   
3. **前端路由集成**
   - 修改 `art-lnb-master/src/views/fresh/orders/index.vue`
   - 复用现有 API 封装和路由配置

---

## 验收标准

### 功能验收
- [x] 订单列表显示打印状态列（未打印/已打印/打印失败）
- [x] 支持批量选择已支付订单
- [x] 点击"批量打印"创建打印任务
- [x] 已有成功打印任务的订单自动跳过
- [x] 支持按打印状态筛选订单
- [x] 打印代理能正常处理批量任务
- [x] 批量打印有明确的成功/失败反馈

### 性能验收
- [x] 批量打印接口响应时间 < 3秒（100个订单）
- [x] 订单列表查询增加打印状态后，响应时间增加 < 500ms

### 兼容性验收
- [x] 不影响现有自动打印功能
- [x] 不影响现有单个任务重试功能
- [x] 打印代理无需升级

---

## 任务边界限制

### 包含在范围内
✅ 后端批量打印接口开发  
✅ 订单列表打印状态显示  
✅ 批量选择和打印 UI  
✅ 打印状态筛选功能  
✅ 错误处理和用户反馈  

### 不包含在范围内
❌ 修改打印代理程序  
❌ 修改打印小票样式  
❌ 增加新的打印机型号支持  
❌ 细粒度权限控制系统  
❌ 打印历史报表统计  

---

## 关键假设确认

1. ✅ **打印状态计算逻辑**
   - NONE：订单无打印任务
   - PENDING：有 PENDING/PRINTING/RETRYING 状态的任务
   - SUCCESS：有 SUCCESS 状态的任务
   - FAILED：仅有 FAILED 状态的任务，无 SUCCESS

2. ✅ **批量打印业务规则**
   - 仅处理 `status = 'PAID'` 的订单
   - 跳过已有 SUCCESS 打印任务的订单
   - 失败任务的订单可重新加入批量打印
   - 部分失败不影响其他订单的打印任务创建

3. ✅ **用户交互流程**
   - 管理员进入订单管理页面
   - 通过筛选器查看"未打印"订单
   - 勾选需要打印的订单
   - 点击"批量打印"按钮
   - 弹窗确认打印数量
   - 提交后显示成功/失败数量
   - 打印状态列实时更新

---

## 所有不确定性已解决

✅ 批量打印仅限已支付订单  
✅ 复用现有管理员认证，无需独立权限  
✅ 打印状态独立列显示  
✅ 功能集成在订单管理页面  
✅ 打印状态不持久化，通过关联查询获取  
✅ 避免重复打印的逻辑明确  

---

**共识确认时间**：2026-07-25  
**下一阶段**：架构设计（Architect）
