# 微信小程序客户端 UI 原型解析

## 1. 原型来源

- 原始导出包：`design/prototypes/wechat-miniapp-ui-prototype-stitch.zip`
- 解压目录：`design/prototypes/wechat-miniapp-ui-prototype-stitch-extracted/stitch_`
- 规范截图目录：`design/screenshots/wechat-miniapp-client`
- 设计系统文档：`design/prototypes/wechat-miniapp-ui-prototype-stitch-extracted/stitch_/fresh_delivery_design_system/DESIGN.md`

## 2. 页面映射

| Stitch 目录 | 页面职责 | 小程序建议页面路径 | 截图 |
| --- | --- | --- | --- |
| `_2` | 首页 | `pages/home/index` | 原截图损坏，仅参考 `code.html` |
| `_1` | 分类商品页 | `pages/category/index` | `design/screenshots/wechat-miniapp-client/category-products.png` |
| `_3` | 商品详情页 | `pages/product-detail/index` | `design/screenshots/wechat-miniapp-client/product-detail.png` |
| `_10` | 加购弹窗 | 组件：`components/cart-add-sheet` | `design/screenshots/wechat-miniapp-client/add-cart-modal.png` |
| `_5` | 购物车页，普通底部栏版本 | `pages/cart/index` | `design/screenshots/wechat-miniapp-client/cart-standard.png` |
| `_6` | 购物车页，浮动结算栏版本 | `pages/cart/index` | `design/screenshots/wechat-miniapp-client/cart-floating.png` |
| `_7` | 确认订单页 | `pages/checkout/index` | `design/screenshots/wechat-miniapp-client/checkout.png` |
| `_4` | 订单列表页 | `pages/orders/index` | `design/screenshots/wechat-miniapp-client/order-list.png` |
| `_11` | 订单详情页 | `pages/order-detail/index` | `design/screenshots/wechat-miniapp-client/order-detail.png` |
| `_12` | 退款申请页 | `pages/refund-apply/index` | `design/screenshots/wechat-miniapp-client/refund-apply.png` |
| `_8` | 我的页面 | `pages/profile/index` | `design/screenshots/wechat-miniapp-client/profile.png` |
| `_9` | 地址管理页 | `pages/address/index` | `design/screenshots/wechat-miniapp-client/addresses.png` |

## 3. 采用建议

### 3.1 购物车版本

优先采用 `_6` 的浮动结算栏版本。

原因：

- 更符合移动端手持操作习惯。
- 结算按钮更突出。
- 底部 Tab 和结算区层级更清晰。

`_5` 可作为备选参考，不建议两个版本都实现。

### 3.2 首页

`_2/screen.png` 文件损坏，只有 28 bytes，不能作为截图参考。

但 `_2/code.html` 内容完整，可以继续作为首页结构依据。首页核心结构为：

- 顶部门店名称「禹邻优鲜」
- 搜索框
- 轮播图「今日新鲜到店」
- 五宫格分类入口
- 今日推荐商品双列卡片
- 底部 Tab

## 4. 视觉规范

### 4.1 颜色

| 用途 | 色值 | 说明 |
| --- | --- | --- |
| 主绿色 | `#006D37` | 品牌主色、价格、主要图标 |
| 行动绿色 | `#27AE60` | 主按钮、加购按钮 |
| 页面背景 | `#F8F9FB` | 小程序背景 |
| 卡片背景 | `#FFFFFF` | 商品卡片、订单卡片 |
| 浅灰容器 | `#F2F4F6` | 分类侧栏、输入框底色 |
| 正文 | `#191C1E` | 主文本 |
| 次级文本 | `#3D4A3F` | 说明、规格、地址 |
| 错误色 | `#BA1A1A` | 退款、取消、异常提示 |

### 4.2 字体

小程序落地时统一使用系统字体：

```css
font-family: -apple-system, BlinkMacSystemFont, "PingFang SC", "Helvetica Neue", Arial, sans-serif;
```

建议字号：

| 场景 | 字号 | 字重 |
| --- | --- | --- |
| 页面标题 | 36rpx | 600 |
| 分区标题 | 32rpx | 600 |
| 商品名称 | 28rpx | 500 |
| 正文 | 28rpx | 400 |
| 辅助文本 | 24rpx | 400 |
| 价格 | 36rpx-48rpx | 700 |

### 4.3 间距与圆角

- 页面左右边距：`24rpx`
- 卡片内边距：`24rpx`
- 商品网格间距：`16rpx`
- 卡片圆角：`16rpx-24rpx`
- 图片圆角：`16rpx`
- 主按钮圆角：胶囊按钮，`999rpx`
- 最小点击区域：不小于 `88rpx`

## 5. 组件拆分建议

### 5.1 基础组件

- `AppTabBar`：底部 Tab，包含首页、分类、购物车、订单、我的。
- `SearchBar`：搜索框。
- `ProductCard`：商品卡片，支持商品图、名称、规格、价格、加购按钮。
- `QuantityStepper`：数量加减器，必须支持小数步进。
- `CartAddSheet`：加购底部弹窗。
- `OrderCard`：订单卡片。
- `AddressCard`：地址卡片。
- `PriceRow`：金额明细行。
- `EmptyState`：空状态。

### 5.2 商品卡片规则

商品卡片必须支持以下字段：

- 商品图片
- 商品名称
- 销售单位
- 单位价格
- 最小购买量
- 加购步进值
- 库存状态
- 标签，可选

价格展示格式：

```text
￥3.99/斤
￥4.80/份
```

注意：Stitch 设计系统文档中出现过货币符号被误写为 `楼` 的问题，开发时统一使用 `￥` 或 `¥`。

### 5.3 数量选择规则

数量选择器不能写死整数加减，需要按商品配置计算：

```text
初始数量 = minPurchaseQty
每次增加 = stepQty
每次减少 = stepQty
最低数量 = minPurchaseQty
最高数量 <= stockQty
```

示例：

| 商品 | 单位 | 最小购买量 | 步进值 |
| --- | --- | --- | --- |
| 西红柿 | 斤 | 0.5 | 0.5 |
| 草莓 | 盒 | 1 | 1 |
| 生菜 | 份 | 1 | 1 |

金额计算必须以后端返回为准，前端仅用于展示预估金额。

## 6. 页面落地规则

### 6.1 首页

落地重点：

- 保留顶部固定搜索和门店品牌。
- 分类入口按后台配置渲染。
- 推荐商品复用 `ProductCard`。
- 首页商品图必须使用后台配置图，不直接使用 Stitch 外链。

### 6.2 分类商品页

落地重点：

- 左侧分类固定宽度，建议 `168rpx`。
- 右侧双列商品网格。
- 商品列表支持分类切换、关键词搜索、排序。
- 底部 Tab 浮层不能遮挡最后一行商品，需要给列表底部增加安全留白。

### 6.3 加购弹窗

落地重点：

- 点击商品卡片 `+` 时打开。
- 展示商品图、名称、价格、当前选择数量、小计。
- 数量增减必须按商品步进值。
- 确认后写入购物车。

### 6.4 购物车页

落地重点：

- 采用 `_6` 浮动结算栏布局。
- 商品支持选中、取消选中、删除、数量调整。
- 底部合计金额随选中商品变化。
- 数量调整时重新校验库存和步进值。

### 6.5 确认订单页

落地重点：

- 收货地址必须选择。
- 预约配送时间必须选择。
- 商品明细、配送费、应付金额清晰展示。
- 底部按钮文案使用「微信支付」。

### 6.6 订单页

落地重点：

- 订单列表按状态 Tab 筛选。
- 订单详情展示状态进度、地址、预约时间、商品明细、金额明细。
- 可操作按钮由订单状态控制，不在前端写死。

### 6.7 退款申请页

落地重点：

- 退款金额不能超过后端返回的可退金额。
- 退款原因使用固定选项。
- 图片凭证可选，一期可以限制最多 3 张。
- 提交后进入售后审核。

## 7. 资源处理

HTML 中共有 36 处图片引用，去重后 34 个商品/头像/凭证图片外链，主要来自 `lh3.googleusercontent.com`。

小程序开发时不要直接使用这些外链，原因：

- 微信小程序需要配置合法下载域名。
- Google 资源在国内网络环境不稳定。
- 商品图应来自门店真实商品或后台上传资源。

建议：

- 原型截图仅作为视觉参考。
- 商品图片由后台上传并返回 CDN 地址。
- 当前可使用本地默认商品图；正式运营时可通过后台商品图片字段维护真实商品图。

## 8. 当前缺口

- 首页截图损坏，但 HTML 可用。
- 未生成独立支付结果页，需要开发时补充。
- 未生成搜索结果页，可复用分类商品页布局。
- 未生成空购物车页，需要开发时补充。
- 未生成订单空状态，需要开发时补充。
- 设计稿存在部分编码或货币符号异常，开发时统一修正为简体中文和 `￥`。

## 9. 下一步开发建议

1. 搭建微信小程序项目结构。
2. 先实现基础主题变量、TabBar、商品卡片、数量选择器。
3. 再按页面优先级开发：首页、分类、商品详情、购物车、确认订单。
4. 最后补订单、地址、退款、我的页面。

推荐首批开发顺序：

```text
主题变量 -> TabBar -> ProductCard -> QuantityStepper -> 分类商品页 -> 加购弹窗 -> 购物车页 -> 确认订单页
```
