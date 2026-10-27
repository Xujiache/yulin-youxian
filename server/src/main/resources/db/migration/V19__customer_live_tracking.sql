-- 顾客侧短轨迹窗口、点数上限，以及动态 ETA 节流。运营可关闭（窗口/上限设为 0）或调节。
INSERT INTO delivery_config
    (config_key, config_value, value_type, category, display_name, description, min_value, max_value)
VALUES
    ('tracking.customer_trail_seconds', '180', 'INT', 'TRACKING', '顾客短轨迹窗口（秒）',
     '发车后向本单顾客下发的清洗轨迹时长，默认 180 秒。设为 0 则不下发短轨迹', '0', '600'),
    ('tracking.customer_trail_max_points', '20', 'INT', 'TRACKING', '顾客短轨迹点数上限',
     '短轨迹最多返回的清洗点数，按时间排序。设为 0 则不下发短轨迹', '0', '50'),
    ('eta.live_recompute_interval_seconds', '30', 'INT', 'ETA', '顾客动态 ETA 节流（秒）',
     '同一波次在站点未变化时，动态 ETA 与剩余路线最多每 30 秒重算一次；站点完成或调序立即失效', '5', '120')
ON DUPLICATE KEY UPDATE
    config_value = VALUES(config_value),
    value_type = VALUES(value_type),
    category = VALUES(category),
    display_name = VALUES(display_name),
    description = VALUES(description),
    min_value = VALUES(min_value),
    max_value = VALUES(max_value);
