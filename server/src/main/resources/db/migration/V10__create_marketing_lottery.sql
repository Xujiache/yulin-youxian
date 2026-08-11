CREATE TABLE marketing_lottery_campaign (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    enabled             TINYINT(1)   NOT NULL DEFAULT 0,
    name                VARCHAR(128) NOT NULL,
    start_at            DATETIME(6)  NULL,
    end_at              DATETIME(6)  NULL,
    daily_user_limit    INT          NOT NULL DEFAULT 1,
    daily_budget_amount INT          NULL COMMENT '每日减免预算，单位分；NULL 表示不限',
    share_title         VARCHAR(128) NULL,
    share_description   VARCHAR(512) NULL,
    share_image_url     VARCHAR(512) NULL,
    created_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE marketing_lottery_tier (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    campaign_id         BIGINT       NOT NULL,
    name                VARCHAR(128) NOT NULL,
    min_product_amount  INT          NOT NULL,
    max_product_amount  INT          NULL COMMENT '左闭右开；NULL 表示无上限',
    enabled             TINYINT(1)   NOT NULL DEFAULT 1,
    sort_order          INT          NOT NULL DEFAULT 100,
    created_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_lottery_tier_campaign (campaign_id, enabled, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE marketing_lottery_prize (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    campaign_id         BIGINT       NOT NULL,
    tier_id             BIGINT       NOT NULL,
    type                VARCHAR(16)  NOT NULL COMMENT 'DISCOUNT/GOODS/NONE',
    name                VARCHAR(128) NOT NULL,
    discount_amount     INT          NULL,
    product_id          BIGINT       NULL COMMENT '软引用 StorefrontSnapshot 商品',
    sku_id              BIGINT       NULL COMMENT '软引用 StorefrontSnapshot SKU',
    image_url           VARCHAR(512) NULL,
    weight              INT          NOT NULL,
    stock_total         INT          NOT NULL DEFAULT 0,
    stock_remaining     INT          NOT NULL DEFAULT 0,
    enabled             TINYINT(1)   NOT NULL DEFAULT 1,
    sort_order          INT          NOT NULL DEFAULT 100,
    created_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_lottery_prize_tier (tier_id, enabled, sort_order),
    KEY idx_lottery_prize_campaign (campaign_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE marketing_lottery_challenge (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    campaign_id         BIGINT       NOT NULL,
    order_id            BIGINT       NOT NULL,
    user_id             BIGINT       NOT NULL,
    challenge_token     VARCHAR(96) COLLATE utf8mb4_bin NOT NULL,
    expires_at          DATETIME(6)  NOT NULL,
    share_triggered_at  DATETIME(6)  NULL,
    consumed_at         DATETIME(6)  NULL,
    created_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_lottery_challenge_token (challenge_token),
    KEY idx_lottery_challenge_order (order_id, user_id, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE marketing_lottery_draw (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    campaign_id         BIGINT       NOT NULL,
    tier_id             BIGINT       NOT NULL,
    prize_id            BIGINT       NOT NULL,
    order_id            BIGINT       NOT NULL,
    order_no            VARCHAR(64)  NOT NULL,
    user_id             BIGINT       NOT NULL,
    product_amount      INT          NOT NULL,
    prize_index         INT          NOT NULL,
    prize_type          VARCHAR(16)  NOT NULL,
    prize_name          VARCHAR(128) NOT NULL,
    discount_amount     INT          NOT NULL DEFAULT 0,
    product_id          BIGINT       NULL,
    sku_id              BIGINT       NULL,
    image_url           VARCHAR(512) NULL,
    payable_before      INT          NOT NULL,
    payable_amount      INT          NOT NULL,
    status              VARCHAR(16)  NOT NULL DEFAULT 'RESERVED' COMMENT 'RESERVED/APPLIED/VOIDED',
    gift_stock_status   VARCHAR(16)  NOT NULL DEFAULT 'NONE' COMMENT 'NONE/RESERVED/RELEASED/FULFILLED',
    void_reason         VARCHAR(256) NULL,
    fulfilled_at        DATETIME(6)  NULL,
    fulfillment_remark VARCHAR(512) NULL,
    created_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_lottery_draw_order (order_id),
    KEY idx_lottery_draw_user_day (user_id, created_at),
    KEY idx_lottery_draw_campaign_day (campaign_id, created_at, status),
    KEY idx_lottery_draw_prize (prize_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE marketing_lottery_gift (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    draw_id             BIGINT       NOT NULL,
    prize_id            BIGINT       NOT NULL,
    order_id            BIGINT       NOT NULL,
    product_id          BIGINT       NOT NULL,
    sku_id              BIGINT       NULL,
    product_name        VARCHAR(128) NOT NULL,
    sku_name            VARCHAR(256) NULL,
    image_url           VARCHAR(512) NULL,
    quantity            DECIMAL(12,3) NOT NULL DEFAULT 1.000,
    status              VARCHAR(16)  NOT NULL DEFAULT 'RESERVED',
    created_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_lottery_gift_draw (draw_id),
    KEY idx_lottery_gift_order (order_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE marketing_lottery_order_decision (
    order_id            BIGINT       NOT NULL,
    user_id             BIGINT       NOT NULL,
    campaign_id         BIGINT       NOT NULL,
    decision            VARCHAR(16)  NOT NULL COMMENT 'DRAWN/SKIPPED',
    draw_id             BIGINT       NULL,
    locked_at           DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (order_id),
    KEY idx_lottery_decision_user (user_id, locked_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE delivery_task
    ADD COLUMN marketing_discount_amount INT NOT NULL DEFAULT 0 COMMENT '订单随机减免快照，单位分';

ALTER TABLE delivery_task
    ADD COLUMN marketing_gift_summary VARCHAR(512) NULL COMMENT '零元赠品履约摘要';

INSERT INTO marketing_lottery_campaign (
    enabled, name, daily_user_limit, daily_budget_amount,
    share_title, share_description, share_image_url
)
SELECT 0, '随机减免', 1, NULL, '分享后抽随机减免', '分享朋友圈菜单触发一次抽奖资格', NULL
WHERE NOT EXISTS (SELECT 1 FROM marketing_lottery_campaign);
