CREATE TABLE rider_location (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    rider_id        BIGINT       NOT NULL,
    shift_id        BIGINT       NULL,
    wave_id         BIGINT       NULL,
    lat             DECIMAL(10,7) NOT NULL,
    lng             DECIMAL(10,7) NOT NULL,
    accuracy_meters INT          NULL,
    speed_mps       DECIMAL(6,2) NULL,
    bearing         DECIMAL(6,2) NULL,
    altitude        DECIMAL(8,2) NULL,
    provider        VARCHAR(24)  NULL COMMENT 'GPS/NETWORK/FUSED/PDR',
    battery_level   INT          NULL,
    network_type    VARCHAR(16)  NULL,
    motion_state    VARCHAR(16)  NULL COMMENT 'STILL/WALKING/RIDING',
    is_cleaned      TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否已通过清洗',
    located_at      DATETIME(6)  NOT NULL COMMENT '设备定位时刻，非入库时刻',
    reported_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    batch_key       VARCHAR(64)  NULL COMMENT '客户端批次幂等键',
    PRIMARY KEY (id),
    -- uk_location_dedup 同时承担 (rider_id, located_at) 的查询索引，不再另建重复索引
    UNIQUE KEY uk_location_dedup (rider_id, located_at),
    KEY idx_location_wave (wave_id, located_at),
    KEY idx_location_cleanup (located_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE rider_location_latest (
    rider_id        BIGINT       NOT NULL,
    lat             DECIMAL(10,7) NOT NULL,
    lng             DECIMAL(10,7) NOT NULL,
    accuracy_meters INT          NULL,
    speed_mps       DECIMAL(6,2) NULL,
    bearing         DECIMAL(6,2) NULL,
    battery_level   INT          NULL,
    motion_state    VARCHAR(16)  NULL,
    wave_id         BIGINT       NULL,
    current_task_id BIGINT       NULL,
    located_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (rider_id),
    KEY idx_latest_time (located_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE delivery_geofence_event (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    rider_id        BIGINT       NOT NULL,
    task_id         BIGINT       NULL,
    zone_type       VARCHAR(24)  NOT NULL COMMENT 'STORE/CUSTOMER/MARKED_ZONE',
    event_type      VARCHAR(24)  NOT NULL COMMENT 'ENTER/EXIT/DWELL',
    lat             DECIMAL(10,7) NOT NULL,
    lng             DECIMAL(10,7) NOT NULL,
    distance_meters INT          NULL,
    dwell_seconds   INT          NULL,
    auto_action     VARCHAR(32)  NULL COMMENT '触发的自动动作，如 MARK_ARRIVED',
    occurred_at     DATETIME(6)  NOT NULL,
    created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_geofence_task (task_id, occurred_at),
    KEY idx_geofence_rider (rider_id, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
