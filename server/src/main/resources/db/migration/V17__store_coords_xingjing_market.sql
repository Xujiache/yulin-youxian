-- 门店对准腾讯地图「兴泾镇市场」POI（兴业路122号 / 育才路店）。
-- 坐标取骑手 QS000002 在该农贸市场时段内 3 米精度的静止点（GCJ-02）。
UPDATE delivery_config
SET config_value = '38.385655',
    description = '门店纬度，GCJ-02。兴泾镇市场（兴泾派出所北100米东北方向101米）。可在高德长按店门口后在本页微调'
WHERE config_key = 'store.lat'
  AND config_value IN ('38.384286', '38.383423', '43.807787');

UPDATE delivery_config
SET config_value = '106.100012',
    description = '门店经度，GCJ-02。兴泾镇市场（兴泾派出所北100米东北方向101米）。可在高德长按店门口后在本页微调'
WHERE config_key = 'store.lng'
  AND config_value IN ('106.096557', '106.096144', '87.646187');
