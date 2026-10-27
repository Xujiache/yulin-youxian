-- 门店坐标改为运营提供的兴泾镇市场精确点（GCJ-02）。
UPDATE delivery_config
SET config_value = '38.3864669',
    description = '门店纬度，GCJ-02。兴泾镇市场。可在高德长按店门口后在本页微调'
WHERE config_key = 'store.lat'
  AND config_value IN ('38.385655', '38.384286', '38.383423', '43.807787');

UPDATE delivery_config
SET config_value = '106.0971672',
    description = '门店经度，GCJ-02。兴泾镇市场。可在高德长按店门口后在本页微调'
WHERE config_key = 'store.lng'
  AND config_value IN ('106.100012', '106.096557', '106.096144', '87.646187');
