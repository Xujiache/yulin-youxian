# 数据库表结构草案

## 1. 设计约定

- 金额统一使用 `int`，单位为分。
- 数量使用 `decimal(10,3)`，兼容 `0.5 斤`、`1.25kg` 等小数数量。
- 软删除字段统一使用 `deleted_at`。
- 时间字段统一使用 `created_at`、`updated_at`。
- 微信支付、退款、订单状态变更必须记录流水或日志。

## 2. 用户与地址

### 2.1 users

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | bigint | 主键 |
| openid | varchar(64) | 微信 openid |
| unionid | varchar(64) | 微信 unionid，可空 |
| nickname | varchar(64) | 昵称 |
| avatar_url | varchar(255) | 头像 |
| phone | varchar(20) | 手机号 |
| status | tinyint | 状态：1 正常，0 禁用 |
| created_at | datetime | 创建时间 |
| updated_at | datetime | 更新时间 |

### 2.2 user_addresses

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | bigint | 主键 |
| user_id | bigint | 用户 ID |
| receiver_name | varchar(32) | 收货人 |
| receiver_phone | varchar(20) | 收货电话 |
| province | varchar(32) | 省 |
| city | varchar(32) | 市 |
| district | varchar(32) | 区 |
| detail | varchar(255) | 详细地址 |
| latitude | decimal(10,6) | 纬度，可空 |
| longitude | decimal(10,6) | 经度，可空 |
| is_default | tinyint | 是否默认 |
| created_at | datetime | 创建时间 |
| updated_at | datetime | 更新时间 |
| deleted_at | datetime | 删除时间 |

## 3. 商品

### 3.1 product_categories

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | bigint | 主键 |
| name | varchar(64) | 分类名称 |
| image_url | varchar(255) | 分类图标，可空 |
| sort_order | int | 排序 |
| status | tinyint | 状态：1 启用，0 禁用 |
| created_at | datetime | 创建时间 |
| updated_at | datetime | 更新时间 |
| deleted_at | datetime | 删除时间 |

### 3.2 products

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | bigint | 主键 |
| category_id | bigint | 分类 ID |
| name | varchar(128) | 商品名称 |
| cover_url | varchar(255) | 商品主图 |
| images_json | json | 商品图片 |
| description | text | 商品描述 |
| sale_unit | varchar(16) | 销售单位：斤、份、盒、袋等 |
| unit_price | int | 单位价格，分 |
| min_purchase_qty | decimal(10,3) | 最小购买量 |
| step_qty | decimal(10,3) | 加购步进值 |
| stock_qty | decimal(10,3) | 库存数量 |
| sold_qty | decimal(10,3) | 已售数量 |
| status | tinyint | 状态：1 上架，0 下架 |
| sort_order | int | 排序 |
| created_at | datetime | 创建时间 |
| updated_at | datetime | 更新时间 |
| deleted_at | datetime | 删除时间 |

## 4. 购物车

### 4.1 cart_items

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | bigint | 主键 |
| user_id | bigint | 用户 ID |
| product_id | bigint | 商品 ID |
| quantity | decimal(10,3) | 购买数量 |
| selected | tinyint | 是否选中 |
| created_at | datetime | 创建时间 |
| updated_at | datetime | 更新时间 |

## 5. 订单

### 5.1 orders

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | bigint | 主键 |
| order_no | varchar(32) | 订单号 |
| user_id | bigint | 用户 ID |
| status | varchar(32) | 订单状态 |
| product_amount | int | 商品金额，分 |
| delivery_fee | int | 配送费，分 |
| discount_amount | int | 优惠金额，分，一期可为 0 |
| payable_amount | int | 应付金额，分 |
| paid_amount | int | 实付金额，分 |
| refunded_amount | int | 已退金额，分 |
| receiver_name | varchar(32) | 收货人快照 |
| receiver_phone | varchar(20) | 收货电话快照 |
| receiver_address | varchar(255) | 收货地址快照 |
| delivery_slot_id | bigint | 预约时间段 ID |
| delivery_slot_label | varchar(64) | 预约时间段快照 |
| remark | varchar(255) | 用户备注 |
| paid_at | datetime | 支付时间 |
| accepted_at | datetime | 接单时间 |
| delivered_at | datetime | 开始配送时间 |
| completed_at | datetime | 完成时间 |
| canceled_at | datetime | 取消时间 |
| created_at | datetime | 创建时间 |
| updated_at | datetime | 更新时间 |

### 5.2 order_items

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | bigint | 主键 |
| order_id | bigint | 订单 ID |
| product_id | bigint | 商品 ID |
| product_name | varchar(128) | 商品名称快照 |
| product_cover_url | varchar(255) | 商品图片快照 |
| sale_unit | varchar(16) | 销售单位快照 |
| unit_price | int | 单位价格快照，分 |
| quantity | decimal(10,3) | 购买数量 |
| amount | int | 商品行金额，分 |
| refund_amount | int | 已退金额，分 |
| created_at | datetime | 创建时间 |
| updated_at | datetime | 更新时间 |

### 5.3 order_status_logs

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | bigint | 主键 |
| order_id | bigint | 订单 ID |
| from_status | varchar(32) | 原状态 |
| to_status | varchar(32) | 新状态 |
| operator_type | varchar(16) | 操作方：user、admin、system、wechat |
| operator_id | bigint | 操作方 ID，可空 |
| remark | varchar(255) | 备注 |
| created_at | datetime | 创建时间 |

## 6. 支付与退款

### 6.1 payments

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | bigint | 主键 |
| order_id | bigint | 订单 ID |
| order_no | varchar(32) | 订单号 |
| payment_no | varchar(64) | 本地支付单号 |
| wechat_transaction_id | varchar(64) | 微信支付交易号 |
| amount | int | 支付金额，分 |
| status | varchar(32) | 支付状态 |
| prepay_id | varchar(128) | 微信 prepay_id |
| paid_at | datetime | 支付完成时间 |
| created_at | datetime | 创建时间 |
| updated_at | datetime | 更新时间 |

### 6.2 refunds

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | bigint | 主键 |
| order_id | bigint | 订单 ID |
| refund_no | varchar(64) | 本地退款单号 |
| wechat_refund_id | varchar(64) | 微信退款单号 |
| refund_amount | int | 退款金额，分 |
| reason | varchar(255) | 退款原因 |
| status | varchar(32) | 退款状态 |
| requested_by | varchar(16) | 发起方：user、admin、system |
| approved_by | bigint | 审核管理员 ID，可空 |
| refunded_at | datetime | 退款完成时间 |
| created_at | datetime | 创建时间 |
| updated_at | datetime | 更新时间 |

## 7. 配送与运营配置

### 7.1 delivery_slots

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | bigint | 主键 |
| label | varchar(64) | 展示名称，例如 今日 09:00-11:00 |
| start_time | time | 开始时间 |
| end_time | time | 结束时间 |
| day_offset | int | 日期偏移：0 今日，1 明日 |
| max_orders | int | 最大订单数，可空 |
| status | tinyint | 状态：1 启用，0 禁用 |
| sort_order | int | 排序 |
| created_at | datetime | 创建时间 |
| updated_at | datetime | 更新时间 |

### 7.2 banners

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | bigint | 主键 |
| title | varchar(64) | 标题 |
| image_url | varchar(255) | 图片 |
| link_type | varchar(32) | 跳转类型 |
| link_value | varchar(255) | 跳转值 |
| sort_order | int | 排序 |
| status | tinyint | 状态 |
| created_at | datetime | 创建时间 |
| updated_at | datetime | 更新时间 |

### 7.3 system_settings

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | bigint | 主键 |
| setting_key | varchar(64) | 配置键 |
| setting_value | text | 配置值 |
| description | varchar(255) | 配置说明 |
| created_at | datetime | 创建时间 |
| updated_at | datetime | 更新时间 |

## 8. 管理员

### 8.1 admin_users

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | bigint | 主键 |
| username | varchar(64) | 登录名 |
| password_hash | varchar(255) | 密码哈希 |
| real_name | varchar(64) | 姓名 |
| role | varchar(32) | 角色 |
| status | tinyint | 状态 |
| last_login_at | datetime | 最后登录时间 |
| created_at | datetime | 创建时间 |
| updated_at | datetime | 更新时间 |

### 8.2 admin_operation_logs

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | bigint | 主键 |
| admin_id | bigint | 管理员 ID |
| action | varchar(64) | 操作 |
| target_type | varchar(64) | 目标类型 |
| target_id | bigint | 目标 ID |
| detail_json | json | 操作详情 |
| created_at | datetime | 创建时间 |
