-- 高德 Key 的下发通路（12 §2）：JS API Key 与 Web 服务 Key 通过
-- /api/admin/delivery/configs 按 AMAP 分类下发给管理后台。
-- DeliveryConfigDao.updateValue 只能改已存在的键，所以这两行必须先落库，
-- 否则即使配了 Key 也没有可写入的位置，后台地图永远显示「未配置地图 Key」。
-- 默认空串：空值时后台地图退化为占位提示，路径规划退化为直线距离估算，其余功能不受影响。
INSERT INTO delivery_config
    (config_key, config_value, value_type, category, display_name, description, min_value, max_value)
VALUES
    ('amap.js_key', '', 'STRING', 'AMAP', '高德 JS API Key（管理后台地图）', '管理后台调度台、波次详情、骑手轨迹的地图底图 Key。留空时后台地图显示占位提示，派单、轨迹、结算等其他功能均不受影响', NULL, NULL),
    ('amap.web_key', '', 'STRING', 'AMAP', '高德 Web 服务 Key（路径规划）', '服务端调用高德距离矩阵与路径规划的 Key。留空时按 Haversine 直线距离乘绕路系数估算，ETA 略粗但可正常派单', NULL, NULL);
