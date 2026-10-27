-- 骑手端异常上报的幂等键。
--
-- 骑手端在网络不稳时会带着同一个 clientEventId 自动重试（RetryInterceptor 允许重试
-- 带该字段的 POST，离线队列也会重放），但服务端此前从未读过它，每次重试都会新插一条
-- 异常单。表现是同一次「货品破损」在调度台上出现好几条，附带的照片还会因为凭证已被
-- 前一条绑定而永久失败。
--
-- 唯一索引建在可空列上：MySQL 允许多个 NULL，所以管理端/系统产生的、没有幂等键的
-- 异常不受影响。
ALTER TABLE delivery_exception
    ADD COLUMN client_event_id VARCHAR(64) NULL COMMENT '骑手端幂等键，重放时用于去重';

CREATE UNIQUE INDEX uk_delivery_exception_client_event
    ON delivery_exception (client_event_id);

-- 路径规划目标值扩容。
--
-- 目标函数里迟到惩罚是 权重 × 迟到秒数²，默认权重 5 时迟到约 12.5 小时就会超出
-- DECIMAL(14,4) 的范围，整条规划事务回滚，波次的规划里程长期停在 0。
-- 严重逾期恰恰是最需要留下规划记录的场景，不能因为数值装不下就整条丢掉。
ALTER TABLE route_plan
    MODIFY COLUMN objective_value DECIMAL(24,4) NULL COMMENT '目标函数值';
