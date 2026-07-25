# TODO - 打印策略配置

## 待办事项清单

### 必需完成项（启动服务前）

#### 1. 环境检查
- [ ] 确认 Java 环境正常（JDK 17+）
- [ ] 确认 Node.js 环境正常（Node 20+）
- [ ] 确认 Maven 可用
- [ ] 确认 pnpm 可用

### 必需完成项（功能测试）

#### 2. 启动服务
```bash
# 启动后端服务
cd server
./mvnw.cmd spring-boot:run

# 启动前端服务（新终端）
cd art-lnb-master
npm run dev
```

#### 3. 集成测试（T10）
按照 `FINAL_打印策略配置.md` 中的测试场景执行：
- [ ] 场景 1：正常批量打印流程
- [ ] 场景 2：打印状态筛选
- [ ] 场景 3：跳过已打印订单
- [ ] 场景 4：部分失败处理

#### 4. 问题修复（如有）
- [ ] 记录测试中发现的问题
- [ ] 修复问题并重新测试
- [ ] 确认所有测试场景通过

### 可选完成项

#### 5. 性能测试（可选）
- [ ] 测试订单列表加载时间（目标 < 2s）
- [ ] 测试批量打印 10 个订单响应时间（目标 < 3s）
- [ ] 测试批量打印 50 个订单的表现

#### 6. 代码提交
```bash
# 切换回 main 分支（或创建新分支）
git checkout -b feature/batch-print-orders

# 添加变更文件
git add server/src/main/java/com/xianda/freshdelivery/dto/AdminOrderDto.java
git add server/src/main/java/com/xianda/freshdelivery/dto/PrintModels.java
git add server/src/main/java/com/xianda/freshdelivery/service/PrintJobService.java
git add server/src/main/java/com/xianda/freshdelivery/service/StorefrontService.java
git add server/src/main/java/com/xianda/freshdelivery/controller/admin/AdminOrderController.java
git add server/src/main/java/com/xianda/freshdelivery/controller/admin/AdminPrintController.java
git add art-lnb-master/src/api/admin.ts
git add art-lnb-master/src/views/fresh/orders/index.vue
git add docs/打印策略配置/

# 提交
git commit -m "feat: add batch print orders functionality

- Add printStatus and printJobId fields to AdminOrderDto
- Add batch print methods in PrintJobService
- Add batch print endpoint in AdminPrintController
- Add batch print UI in orders management page
- Support print status filtering
- Support batch selection and print

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

#### 7. 创建 Pull Request（可选）
- [ ] 推送到远程分支
- [ ] 创建 PR 到 main 分支
- [ ] 填写 PR 描述（参考 FINAL 文档）
- [ ] 等待 Code Review

---

## 注意事项

### ⚠️ 重要提醒

1. **API 密钥管理**
   - 确保所有敏感信息在 `.env` 文件中
   - 不要提交 `.env` 文件到 Git

2. **测试环境隔离**
   - 建议使用测试数据库测试批量打印
   - 避免在生产环境直接测试

3. **打印代理依赖**
   - 完整功能需要打印代理运行
   - 测试可以只验证任务创建，无需实际打印

4. **浏览器兼容性**
   - 建议使用 Chrome 或 Edge 浏览器
   - 确保浏览器支持现代 JavaScript 特性

---

## 快速启动指引

### 方式 1：仅测试后端接口（推荐先执行）
```bash
# 启动后端
cd server
./mvnw.cmd spring-boot:run

# 使用 Postman 或 curl 测试接口
# 1. 获取订单列表（带打印状态）
curl http://localhost:8080/api/admin/orders?printStatus=NONE

# 2. 批量打印订单
curl -X POST http://localhost:8080/api/admin/printing/orders/batch \
  -H "Content-Type: application/json" \
  -d '{"orderIds": [1001, 1002, 1003]}'
```

### 方式 2：完整功能测试
```bash
# 终端 1：启动后端
cd server
./mvnw.cmd spring-boot:run

# 终端 2：启动前端
cd art-lnb-master
npm run dev

# 浏览器访问
# http://localhost:5173/#/fresh/orders
```

---

## 已知配置需求

### 后端配置
无需额外配置，使用默认配置即可。

### 前端配置
无需额外配置，API 路径已正确设置。

### 打印代理配置（可选）
如需测试完整打印流程：
1. 运行打印代理程序
2. 配置代理密钥
3. 确保打印机连接正常

---

**更新时间**：2026-07-25  
**下次检查**：完成 T10 集成测试后更新此文档
