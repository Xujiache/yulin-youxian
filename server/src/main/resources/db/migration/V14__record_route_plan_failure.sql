-- 路径规划失败的留痕。
--
-- RoutingWaveAdapter 在规划抛异常时只打一行 warn 就算了，波次照常派出去，
-- 但 polyline、路段里程、站点顺序全是空的。骑手端看到的是一张只有孤零零几个点、
-- 没有路线的地图，调度台那边完全不知道发生过什么 —— 这个问题就是这么一直没被发现的。
--
-- 单独一张表而不是往 delivery_wave 加列：这是诊断信息不是业务字段，
-- 规划成功就该整行消失，用「有没有这行」表达状态比留一堆 NULL 列干净。
CREATE TABLE delivery_route_plan_failure (
    wave_id        BIGINT       NOT NULL PRIMARY KEY,
    trigger_reason VARCHAR(48)  NOT NULL COMMENT 'INITIAL/NEW_TASK/REASSIGN/DEVIATION/EXCEPTION/MANUAL',
    error_message  VARCHAR(512) NOT NULL,
    attempt_count  INT          NOT NULL DEFAULT 1 COMMENT '连续失败次数，一直涨说明不是偶发',
    first_failed_at DATETIME(6) NOT NULL,
    last_failed_at DATETIME(6)  NOT NULL,
    INDEX idx_route_plan_failure_last (last_failed_at)
) COMMENT '波次路径规划失败留痕，规划成功后删除';
