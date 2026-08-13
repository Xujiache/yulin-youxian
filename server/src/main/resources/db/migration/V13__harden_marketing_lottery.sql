-- 抽奖并发护栏行。
--
-- 每日预算和用户每日次数此前只靠普通 SELECT 统计，在 MySQL 默认 REPEATABLE READ 下
-- 读到的是事务开始时的快照，并发抽奖可以同时看到 spent=0、count=0，两个上限都能被突破。
-- 这里给每个「活动 + 日期 + 用户」一行护栏，抽奖时先加锁读该行再统计；user_id = 0 的行
-- 代表当天的活动预算。锁粒度从整张 campaign（全局一行，challenge 也要抢）收窄到真正
-- 需要互斥的维度。
CREATE TABLE marketing_lottery_draw_guard (
    campaign_id BIGINT      NOT NULL,
    stat_date   DATE        NOT NULL,
    user_id     BIGINT      NOT NULL COMMENT '0 表示当日活动预算护栏行',
    created_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (campaign_id, stat_date, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 后台流水的审计字段：抽奖生效时间、订单支付完成时间、进入终态的时间。
ALTER TABLE marketing_lottery_draw
    ADD COLUMN applied_at DATETIME(6) NULL COMMENT '奖项写入订单快照并生效的时间';

ALTER TABLE marketing_lottery_draw
    ADD COLUMN paid_at DATETIME(6) NULL COMMENT '订单支付完成时间';

ALTER TABLE marketing_lottery_draw
    ADD COLUMN settled_at DATETIME(6) NULL COMMENT '流水进入 SETTLED 终态的时间';

-- reconcile 扫的是 WHERE status = ?，V10 的 (campaign_id, created_at, status) 前缀对不上，
-- 等于每轮全表扫。补一个 (status, created_at) 供对账的时间窗使用。
CREATE INDEX idx_lottery_draw_status_created ON marketing_lottery_draw (status, created_at);

-- 后台流水列表按 id 倒序分页，同时需要按时间窗过滤。
CREATE INDEX idx_lottery_draw_created ON marketing_lottery_draw (created_at);

-- challenge 表此前只写不删，定时清理按 created_at 走。
CREATE INDEX idx_lottery_challenge_created ON marketing_lottery_challenge (created_at);

-- 后台流水要 join 出分享触发时间。
CREATE INDEX idx_lottery_challenge_share ON marketing_lottery_challenge (order_id, share_triggered_at);

-- 成功且赠品已履约的历史流水直接置为终态，否则 reconcile 的 APPLIED 结果集只增不减。
UPDATE marketing_lottery_draw
SET status = 'SETTLED',
    settled_at = COALESCE(fulfilled_at, updated_at)
WHERE status = 'APPLIED'
  AND gift_stock_status = 'FULFILLED';

-- 历史 APPLIED/SETTLED 流水的生效时间按创建时间回填，避免后台审计列全空。
UPDATE marketing_lottery_draw
SET applied_at = created_at
WHERE applied_at IS NULL
  AND status <> 'RESERVED';
