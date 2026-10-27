CREATE TABLE delivery_settlement (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    settlement_no   VARCHAR(32)  NOT NULL,
    rider_id        BIGINT       NOT NULL,
    period_type     VARCHAR(16)  NOT NULL COMMENT 'DAILY/WEEKLY/MONTHLY',
    period_start    DATE         NOT NULL,
    period_end      DATE         NOT NULL,
    task_count      INT          NOT NULL DEFAULT 0,
    on_time_count   INT          NOT NULL DEFAULT 0,
    base_amount     INT          NOT NULL DEFAULT 0,
    distance_amount INT          NOT NULL DEFAULT 0,
    weight_amount   INT          NOT NULL DEFAULT 0,
    floor_amount    INT          NOT NULL DEFAULT 0,
    weather_amount  INT          NOT NULL DEFAULT 0,
    night_amount    INT          NOT NULL DEFAULT 0,
    holiday_amount  INT          NOT NULL DEFAULT 0,
    bonus_amount    INT          NOT NULL DEFAULT 0,
    adjust_amount   INT          NOT NULL DEFAULT 0 COMMENT '人工调整，可负',
    total_amount    INT          NOT NULL DEFAULT 0,
    status          VARCHAR(24)  NOT NULL DEFAULT 'DRAFT'
                                 COMMENT 'DRAFT/CONFIRMED/PAID/VOID',
    confirmed_at    DATETIME(6)  NULL,
    paid_at         DATETIME(6)  NULL,
    remark          VARCHAR(512) NULL,
    created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_settlement_no (settlement_no),
    UNIQUE KEY uk_settlement_period (rider_id, period_type, period_start),
    KEY idx_settlement_status (status, period_start)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE delivery_settlement_item (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    settlement_id   BIGINT       NULL,
    rider_id        BIGINT       NOT NULL,
    task_id         BIGINT       NULL,
    task_no         VARCHAR(32)  NULL,
    item_type       VARCHAR(32)  NOT NULL
        COMMENT 'BASE/DISTANCE/WEIGHT/FLOOR/WEATHER/NIGHT/HOLIDAY/BONUS/ADJUST',
    amount          INT          NOT NULL DEFAULT 0,
    calc_detail     VARCHAR(512) NULL COMMENT '可读的计算过程，骑手可见',
    occurred_at     DATETIME(6)  NOT NULL,
    created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_item_settlement (settlement_id),
    KEY idx_item_rider_time (rider_id, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE rider_score_event (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    rider_id        BIGINT       NOT NULL,
    task_id         BIGINT       NULL,
    event_code      VARCHAR(32)  NOT NULL
        COMMENT 'ON_TIME/OVERTIME/EXCEPTION_VALID/EXCEPTION_INVALID/BAD_REVIEW/GOOD_REVIEW/TRAINING_DONE/SAFETY_BONUS/RESTORE/MANUAL',
    score_delta     INT          NOT NULL COMMENT '正负分',
    score_after     INT          NOT NULL,
    reason          VARCHAR(256) NULL,
    restorable      TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否可通过准时单/培训恢复',
    restored_at     DATETIME(6)  NULL,
    operator_type   VARCHAR(24)  NOT NULL DEFAULT 'SYSTEM',
    operator_name   VARCHAR(64)  NULL,
    created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_score_rider (rider_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE rider_appeal (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    appeal_no       VARCHAR(32)  NOT NULL,
    rider_id        BIGINT       NOT NULL,
    target_type     VARCHAR(24)  NOT NULL COMMENT 'SCORE_EVENT/OVERTIME/SETTLEMENT/EXCEPTION',
    target_id       BIGINT       NOT NULL,
    reason          VARCHAR(1024) NOT NULL,
    evidence_ids    VARCHAR(256) NULL COMMENT '逗号分隔的 delivery_evidence.id',
    status          VARCHAR(24)  NOT NULL DEFAULT 'PENDING'
                                 COMMENT 'PENDING/APPROVED/REJECTED',
    review_note     VARCHAR(1024) NULL,
    reviewed_by     VARCHAR(64)  NULL,
    reviewed_at     DATETIME(6)  NULL,
    created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_appeal_no (appeal_no),
    KEY idx_appeal_status (status, created_at),
    KEY idx_appeal_rider (rider_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
