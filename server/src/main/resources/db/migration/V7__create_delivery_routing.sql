CREATE TABLE route_plan (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    wave_id             BIGINT       NULL,
    rider_id            BIGINT       NULL,
    plan_version        INT          NOT NULL DEFAULT 1,
    trigger_reason      VARCHAR(48)  NOT NULL
        COMMENT 'INITIAL/NEW_TASK/REASSIGN/DEVIATION/EXCEPTION/MANUAL',
    optimizer_name      VARCHAR(48)  NOT NULL,
    matrix_provider     VARCHAR(48)  NOT NULL,
    stop_count          INT          NOT NULL DEFAULT 0,
    total_distance_meters INT        NOT NULL DEFAULT 0,
    total_duration_seconds INT       NOT NULL DEFAULT 0,
    objective_value     DECIMAL(14,4) NULL COMMENT '目标函数值，便于对比算法效果',
    solve_millis        INT          NOT NULL DEFAULT 0,
    sequence_json       JSON         NOT NULL COMMENT '[{taskId,seq,legDistance,legDuration,etaAt}]',
    polyline            MEDIUMTEXT   NULL COMMENT '编码折线，供 App 与后台画线',
    is_active           TINYINT(1)   NOT NULL DEFAULT 1,
    created_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_route_wave (wave_id, is_active),
    KEY idx_route_time (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE distance_matrix_cache (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    cache_key       VARCHAR(96)  NOT NULL COMMENT 'provider|mode|origin网格|dest网格',
    provider        VARCHAR(48)  NOT NULL,
    travel_mode     VARCHAR(24)  NOT NULL DEFAULT 'EBIKE',
    origin_lat      DECIMAL(10,7) NOT NULL,
    origin_lng      DECIMAL(10,7) NOT NULL,
    dest_lat        DECIMAL(10,7) NOT NULL,
    dest_lng        DECIMAL(10,7) NOT NULL,
    distance_meters INT          NOT NULL,
    duration_seconds INT         NOT NULL,
    hit_count       INT          NOT NULL DEFAULT 0,
    expire_at       DATETIME(6)  NOT NULL,
    created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_matrix_key (cache_key),
    KEY idx_matrix_expire (expire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE building_handoff_stat (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    group_key           VARCHAR(160) NOT NULL COMMENT '与 delivery_task.group_key 一致',
    area_label          VARCHAR(128) NULL,
    building_label      VARCHAR(64)  NULL,
    floor_bucket        VARCHAR(16)  NOT NULL DEFAULT 'ALL' COMMENT 'ALL/1-3/4-6/7-12/13+',
    sample_count        INT          NOT NULL DEFAULT 0,
    avg_handoff_seconds INT          NOT NULL DEFAULT 0,
    p70_handoff_seconds INT          NOT NULL DEFAULT 0,
    p90_handoff_seconds INT          NOT NULL DEFAULT 0,
    has_elevator        TINYINT(1)   NULL,
    access_difficulty   INT          NOT NULL DEFAULT 0 COMMENT '门禁难度 0-5，异常上报累积',
    last_sample_at      DATETIME(6)  NULL,
    created_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_handoff (group_key, floor_bucket),
    KEY idx_handoff_area (area_label)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE geo_poi_cache (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    address_hash    CHAR(64)     NOT NULL COMMENT '规整后地址的 SHA-256',
    raw_address     VARCHAR(512) NOT NULL,
    formatted_address VARCHAR(512) NULL,
    lat             DECIMAL(10,7) NULL,
    lng             DECIMAL(10,7) NULL,
    confidence      INT          NULL,
    provider        VARCHAR(48)  NOT NULL,
    hit_count       INT          NOT NULL DEFAULT 0,
    created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_geo_hash (address_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
