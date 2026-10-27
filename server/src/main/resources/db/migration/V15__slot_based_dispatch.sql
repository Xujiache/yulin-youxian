-- 时段批次派单。
--
-- 自营单店的实际流程是：商家备货 → 后台按时段分单给骑手 → 一键发车 → 骑手整波取货配送
-- → 全部送达回店 → 发下一个时段。原来的调度是为「即时单、系统自动派」设计的，
-- 拣货完成约 120 秒后就把单派走，商家来不及自己分；自动改派还会把分好的单换骑手。

-- 派单模式。默认只推荐不执行：打分、聚类、推荐照常算，但要店主点了才生效。
-- 认不出来的值按 ADVISORY 处理，配置写错时宁可不派也不能替店主做主。
INSERT INTO delivery_config
    (config_key, config_value, value_type, category, display_name, description, min_value, max_value)
VALUES
    ('dispatch.mode', 'ADVISORY', 'STRING', 'DISPATCH', '派单模式',
     'ADVISORY=只推荐不自动派单（自营配送默认）；AUTO=系统自动派单并在超时风险时自动改派',
     NULL, NULL)
ON DUPLICATE KEY UPDATE config_key = config_key;

-- 自动改派默认关掉：推荐模式下它本来就不生效，这里把库里的值一并对齐，
-- 免得日后切到 AUTO 时突然多出一个没人预期的行为。
UPDATE delivery_config SET config_value = 'false'
WHERE config_key = 'dispatch.auto_reassign_enabled';

-- 波次绑定配送时段。原来时段只存在于订单和任务上，波次没有，
-- 所以「按时段发一波车」无从谈起，波次列表也只能按日期筛。
ALTER TABLE delivery_wave
    ADD COLUMN slot_label VARCHAR(64) NULL COMMENT '配送时段，与 delivery_task.slot_label 一致';

CREATE INDEX idx_delivery_wave_slot ON delivery_wave (delivery_date, slot_label);

-- 骑手回店。波次原来在最后一单送达时直接完成，但那时骑手还在最后一个顾客门口，
-- 调度台无从判断他什么时候能接下一个时段。
ALTER TABLE delivery_wave
    ADD COLUMN returned_at DATETIME(6) NULL COMMENT '骑手确认回到门店的时间';
