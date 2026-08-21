-- 固定四奖项转盘：新增槽位/概率/双减免模式字段，并安全迁移现有单活动。
-- 不修改 V10/V13。不删除 legacy 阶梯、奖项和历史流水。

ALTER TABLE marketing_lottery_tier
    ADD COLUMN pool_code VARCHAR(16) NULL;

CREATE UNIQUE INDEX uk_lottery_tier_pool ON marketing_lottery_tier (campaign_id, pool_code);

ALTER TABLE marketing_lottery_prize
    ADD COLUMN prize_code VARCHAR(16) NULL;

ALTER TABLE marketing_lottery_prize
    ADD COLUMN probability_bp INT NULL;

ALTER TABLE marketing_lottery_prize
    ADD COLUMN discount_mode VARCHAR(16) NULL;

ALTER TABLE marketing_lottery_prize
    ADD COLUMN threshold_amount INT NULL;

ALTER TABLE marketing_lottery_prize
    ADD COLUMN fixed_discount_amount INT NULL;

ALTER TABLE marketing_lottery_prize
    ADD COLUMN discount_rate_bp INT NULL;

ALTER TABLE marketing_lottery_prize
    ADD COLUMN max_discount_amount INT NULL;

CREATE UNIQUE INDEX uk_lottery_prize_code ON marketing_lottery_prize (campaign_id, prize_code);

ALTER TABLE marketing_lottery_draw
    ADD COLUMN prize_code VARCHAR(16) NULL;

-- 旧阶梯/奖项停用但保留，供历史 GOODS 流水履约、退款和对账。
UPDATE marketing_lottery_tier
SET enabled = 0
WHERE pool_code IS NULL;

UPDATE marketing_lottery_prize
SET enabled = 0
WHERE prize_code IS NULL;

-- 不改 campaign 的开关、时段、预算、每日次数和分享配置。
INSERT INTO marketing_lottery_tier (
    campaign_id, name, min_product_amount, max_product_amount, enabled, sort_order, pool_code
)
SELECT id, '全部订单', 0, NULL, 1, 10, 'GLOBAL'
FROM marketing_lottery_campaign
ORDER BY id
LIMIT 1;

INSERT INTO marketing_lottery_prize (
    campaign_id, tier_id, type, name, discount_amount, product_id, sku_id, image_url,
    weight, stock_total, stock_remaining, enabled, sort_order,
    prize_code, probability_bp, discount_mode,
    threshold_amount, fixed_discount_amount, discount_rate_bp, max_discount_amount
)
SELECT c.id, t.id, 'DISCOUNT', '一等奖', NULL, NULL, NULL, NULL,
       0, 0, 0, 1, 10,
       'FIRST', 0, 'PERCENTAGE',
       NULL, NULL, NULL, NULL
FROM marketing_lottery_campaign c
JOIN marketing_lottery_tier t ON t.campaign_id = c.id AND t.pool_code = 'GLOBAL'
ORDER BY c.id
LIMIT 1;

INSERT INTO marketing_lottery_prize (
    campaign_id, tier_id, type, name, discount_amount, product_id, sku_id, image_url,
    weight, stock_total, stock_remaining, enabled, sort_order,
    prize_code, probability_bp, discount_mode,
    threshold_amount, fixed_discount_amount, discount_rate_bp, max_discount_amount
)
SELECT c.id, t.id, 'DISCOUNT', '二等奖', NULL, NULL, NULL, NULL,
       0, 0, 0, 1, 20,
       'SECOND', 0, 'PERCENTAGE',
       NULL, NULL, NULL, NULL
FROM marketing_lottery_campaign c
JOIN marketing_lottery_tier t ON t.campaign_id = c.id AND t.pool_code = 'GLOBAL'
ORDER BY c.id
LIMIT 1;

INSERT INTO marketing_lottery_prize (
    campaign_id, tier_id, type, name, discount_amount, product_id, sku_id, image_url,
    weight, stock_total, stock_remaining, enabled, sort_order,
    prize_code, probability_bp, discount_mode,
    threshold_amount, fixed_discount_amount, discount_rate_bp, max_discount_amount
)
SELECT c.id, t.id, 'DISCOUNT', '三等奖', NULL, NULL, NULL, NULL,
       0, 0, 0, 1, 30,
       'THIRD', 0, 'PERCENTAGE',
       NULL, NULL, NULL, NULL
FROM marketing_lottery_campaign c
JOIN marketing_lottery_tier t ON t.campaign_id = c.id AND t.pool_code = 'GLOBAL'
ORDER BY c.id
LIMIT 1;

INSERT INTO marketing_lottery_prize (
    campaign_id, tier_id, type, name, discount_amount, product_id, sku_id, image_url,
    weight, stock_total, stock_remaining, enabled, sort_order,
    prize_code, probability_bp, discount_mode,
    threshold_amount, fixed_discount_amount, discount_rate_bp, max_discount_amount
)
SELECT c.id, t.id, 'NONE', '谢谢惠顾', NULL, NULL, NULL, NULL,
       10000, 0, 0, 1, 40,
       'NONE', 10000, 'NONE',
       NULL, NULL, NULL, NULL
FROM marketing_lottery_campaign c
JOIN marketing_lottery_tier t ON t.campaign_id = c.id AND t.pool_code = 'GLOBAL'
ORDER BY c.id
LIMIT 1;
