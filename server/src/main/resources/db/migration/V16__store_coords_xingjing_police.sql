-- 把门店从兴泾镇中心/测试点，改到「兴泾派出所北 100 米、再东北 101 米」。
-- 派出所在兴泾镇卫生院西南约 50 米，卫生院在镇政府对面；镇政府公开坐标为 38.383423,106.096144。
UPDATE delivery_config
SET config_value = '38.384286',
    description = '门店纬度，GCJ-02。按兴泾派出所北100米、再东北101米定位。可在高德地图长按店门口后在本页微调'
WHERE config_key = 'store.lat'
  AND config_value IN ('38.383423', '43.807787');

UPDATE delivery_config
SET config_value = '106.096557',
    description = '门店经度，GCJ-02。按兴泾派出所北100米、再东北101米定位。可在高德地图长按店门口后在本页微调'
WHERE config_key = 'store.lng'
  AND config_value IN ('106.096144', '87.646187');
