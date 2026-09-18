# 生鲜商城轮播图 · 2026-09-06

10 张独立 PNG 图片，内置 image_gen 生成。实际尺寸 1942 × 809，约 2.4:1；保留原始生成文件，无二次缩放。自然光食物摄影、石材与亚麻纹理、统一构图和多主题配色。

## 使用

打开 preview.html 查看十图与小程序文字叠加效果。PNG 无内嵌文字；小程序 pages/home/index.wxml 使用 title / subtitle 叠加。

2026-09-06 已通过管理 API 上线全部 10 张。实际投放使用 web/ 下的 1440 × 600 WebP，总计 585342 字节，单张约 46–79 KB。水果主题跳转水果类，其他主题跳转蔬菜类。原先 5 条轮播已保留并停用；before-publish.json 保存原始配置，published.json 保存本次发布结果。PNG 原图用于存档。publish.mjs 有重复发布保护，不要直接重复执行。

| 文件 | 标题 | 副标题 |
| --- | --- | --- |
| 01-leafy-greens.png | 一篮清新 | 把青绿端上餐桌 |
| 02-green-grapes.png | 青提时光 | 一口清甜，慢享日常 |
| 03-citrus.png | 橘色好心情 | 让果香点亮今天 |
| 04-peaches.png | 蜜桃小确幸 | 留一点甜，给自己 |
| 05-pears.png | 梨想的滋味 | 清甜入秋，恰到好处 |
| 06-apples.png | 苹果的日常 | 脆甜相伴，简单美好 |
| 07-mushrooms.png | 山野鲜味 | 一锅鲜香，自有滋味 |
| 08-tomatoes.png | 番茄的红 | 为家常添一抹鲜亮 |
| 09-sweetcorn.png | 金色好食光 | 把香甜装进一日三餐 |
| 10-market-selection.png | 好好吃饭 | 一篮蔬鲜，一餐欢喜 |

## 生成记录

完整的逐图提示词保存于 prompts.json。使用内置 image_gen，每个主题独立调用一次。所示食材为 AI 生成的营销场景图，不代表某批次商品的实拍。
