# 接口清单草案

## 1. 接口约定

- 客户端接口前缀：`/api/wx`
- 管理后台接口前缀：`/api/admin`
- 金额单位：分
- 数量字段：decimal 字符串或数字，后端统一校验精度
- 认证方式：
  - 小程序端：登录后携带用户 token
  - 管理后台：登录后携带管理员 token

## 2. 小程序端接口

### 2.1 登录

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/wx/auth/login` | 微信 code 登录 |
| GET | `/api/wx/profile` | 获取当前用户信息 |

登录请求：

```json
{
  "code": "wx.login 返回的 code",
  "clientId": "本地 development 模式使用的设备标识",
  "nickName": "微信昵称",
  "avatarUrl": "微信头像"
}
```

正式模式下后端使用 `code` 调用微信 `jscode2session` 获取 openId；development 模式下使用 `clientId` 生成稳定开发 openId。

登录响应：

```json
{
  "token": "wx_xxx",
  "userId": 1000,
  "openId": "openid",
  "nickName": "微信用户_1000",
  "avatarUrl": "/assets/products/avatar.png"
}
```

### 2.2 首页与商品

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/wx/home` | 首页数据：轮播、分类、推荐商品 |
| GET | `/api/wx/categories` | 商品分类列表 |
| GET | `/api/wx/products` | 商品列表，支持分类和关键词 |
| GET | `/api/wx/products/{id}` | 商品详情 |

商品列表响应核心字段：

```json
{
  "id": 1,
  "name": "西红柿",
  "coverUrl": "https://example.com/product.jpg",
  "saleUnit": "斤",
  "unitPrice": 399,
  "minPurchaseQty": "0.5",
  "stepQty": "0.5",
  "stockQty": "20",
  "status": 1
}
```

### 2.3 购物车

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/wx/cart` | 获取购物车 |
| POST | `/api/wx/cart/items` | 加入购物车 |
| PUT | `/api/wx/cart/items/{id}` | 修改购物车数量 |
| DELETE | `/api/wx/cart/items/{id}` | 删除购物车商品 |
| PUT | `/api/wx/cart/items/{id}/selected` | 修改选中状态 |
| DELETE | `/api/wx/cart/selected` | 清空已选商品 |

加购请求：

```json
{
  "productId": 1,
  "quantity": "1.5"
}
```

后端校验：

- 商品必须上架。
- 库存必须充足。
- 数量必须大于等于最小购买量。
- 数量必须符合步进值。

### 2.4 地址

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/wx/addresses` | 地址列表 |
| POST | `/api/wx/addresses` | 新增地址 |
| PUT | `/api/wx/addresses/{id}` | 编辑地址 |
| DELETE | `/api/wx/addresses/{id}` | 删除地址 |
| PUT | `/api/wx/addresses/{id}/default` | 设置默认地址 |

### 2.5 配送时间

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/wx/delivery-slots` | 获取可预约配送时间段 |

### 2.6 订单

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/wx/orders/preview` | 订单预览 |
| POST | `/api/wx/orders` | 创建订单 |
| GET | `/api/wx/orders` | 订单列表 |
| GET | `/api/wx/orders/{id}` | 订单详情 |
| POST | `/api/wx/orders/{id}/cancel` | 取消订单 |

创建订单请求：

```json
{
  "addressId": 1,
  "deliverySlotId": 2,
  "remark": "请尽快配送",
  "cartItemIds": [1, 2, 3]
}
```

### 2.7 微信支付

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/wx/orders/{id}/pay` | 创建微信支付参数 |
| POST | `/api/wx/orders/{id}/pay/development-success` | development 模式确认支付成功 |
| POST | `/api/wx/payments/wechat/notify` | 微信支付回调 |

支付接口返回小程序调起支付所需参数：

```json
{
  "orderId": 1,
  "orderNo": "ORDER_NO_FROM_CREATE_ORDER",
  "status": "待支付",
  "timeStamp": "1710000000",
  "nonceStr": "nonce",
  "packageValue": "prepay_id=xxx",
  "signType": "RSA",
  "paySign": "sign",
  "developmentMode": false
}
```

### 2.8 售后退款

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/wx/refunds` | 提交退款申请 |
| GET | `/api/wx/refunds` | 退款申请列表 |
| GET | `/api/wx/refunds/{id}` | 退款申请详情 |

退款申请请求：

```json
{
  "orderId": 1,
  "orderItemIds": [1],
  "refundAmount": 399,
  "reason": "商品损坏"
}
```

## 3. 管理后台接口

### 3.1 管理员登录

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/admin/auth/login` | 管理员登录 |
| POST | `/api/admin/auth/logout` | 退出登录 |
| GET | `/api/admin/auth/profile` | 当前管理员信息 |

### 3.2 数据概览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/admin/dashboard/summary` | 后台首页统计 |

### 3.3 分类管理

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/admin/categories` | 分类列表 |
| POST | `/api/admin/categories` | 新增分类 |
| PUT | `/api/admin/categories/{id}` | 编辑分类 |
| DELETE | `/api/admin/categories/{id}` | 删除分类 |

### 3.4 商品管理

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/admin/products` | 商品列表 |
| POST | `/api/admin/products` | 新增商品 |
| GET | `/api/admin/products/{id}` | 商品详情 |
| PUT | `/api/admin/products/{id}` | 编辑商品 |
| DELETE | `/api/admin/products/{id}` | 删除商品 |
| PUT | `/api/admin/products/{id}/status` | 上架/下架 |
| PUT | `/api/admin/products/{id}/stock` | 调整库存 |

商品保存请求核心字段：

```json
{
  "categoryId": 1,
  "name": "西红柿",
  "coverUrl": "https://example.com/product.jpg",
  "images": [],
  "description": "新鲜西红柿",
  "saleUnit": "斤",
  "unitPrice": 399,
  "minPurchaseQty": "0.5",
  "stepQty": "0.5",
  "stockQty": "20",
  "status": 1,
  "sortOrder": 100
}
```

### 3.5 订单管理

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/admin/orders` | 订单列表 |
| GET | `/api/admin/orders/{id}` | 订单详情 |
| POST | `/api/admin/orders/{id}/accept` | 接单 |
| POST | `/api/admin/orders/{id}/prepare` | 标记备货中 |
| POST | `/api/admin/orders/{id}/deliver` | 标记配送中 |
| POST | `/api/admin/orders/{id}/complete` | 标记完成 |
| POST | `/api/admin/orders/{id}/cancel` | 取消订单 |

订单列表筛选参数：

- `status`
- `keyword`
- `deliveryDate`
- `deliverySlotId`
- `createdFrom`
- `createdTo`

### 3.6 退款管理

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/admin/refunds` | 退款列表 |
| GET | `/api/admin/refunds/{id}` | 退款详情 |
| POST | `/api/admin/refunds/{id}/approve` | 审核通过并发起微信退款 |
| POST | `/api/admin/refunds/{id}/reject` | 审核拒绝 |
| POST | `/api/wx/refunds/wechat/notify` | 微信退款回调 |

### 3.7 配送时间段配置

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/admin/delivery-slots` | 时间段列表 |
| POST | `/api/admin/delivery-slots` | 新增时间段 |
| PUT | `/api/admin/delivery-slots/{id}` | 编辑时间段 |
| DELETE | `/api/admin/delivery-slots/{id}` | 删除时间段 |
| PUT | `/api/admin/delivery-slots/{id}/status` | 启用/禁用 |

### 3.8 运营配置

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/admin/settings` | 配置列表 |
| PUT | `/api/admin/settings` | 批量保存配置 |

## 4. 幂等与安全要求

- 微信支付回调按微信交易号幂等。
- 微信退款回调按微信退款单号幂等。
- 订单状态变更必须校验当前状态是否合法。
- 后台退款金额不能超过订单可退金额。
- 用户只能访问自己的订单、地址、退款记录。
- 后台接口必须校验管理员 token。
- 管理后台关键操作写入操作日志。
