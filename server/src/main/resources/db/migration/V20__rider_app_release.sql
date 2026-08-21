-- 骑手 Android 版本历史：元数据全留，当前指针可回滚；APK 实体在应用层只保留最近一批。
CREATE TABLE rider_app_release (
    id                      BIGINT       NOT NULL AUTO_INCREMENT,
    channel                 VARCHAR(32)  NOT NULL DEFAULT 'production',
    version_name            VARCHAR(32)  NOT NULL,
    version_code            INT          NOT NULL,
    title                   VARCHAR(128) NOT NULL,
    notes                   TEXT         NULL,
    policy                  VARCHAR(16)  NOT NULL DEFAULT 'OPTIONAL',
    package_name            VARCHAR(128) NOT NULL,
    file_name               VARCHAR(255) NOT NULL,
    file_path               VARCHAR(512) NULL,
    file_url                VARCHAR(512) NOT NULL,
    file_size               BIGINT       NOT NULL,
    file_sha256             CHAR(64)     NOT NULL,
    cert_sha256             CHAR(64)     NOT NULL,
    source_sha              VARCHAR(64)  NULL,
    status                  VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    published_by            VARCHAR(64)  NULL,
    published_at            DATETIME(6)  NULL,
    notify_count            INT          NOT NULL DEFAULT 0,
    last_notified_at        DATETIME(6)  NULL,
    created_at              DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at              DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_rider_app_channel_version (channel, version_code),
    KEY idx_rider_app_channel_status (channel, status, version_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE rider_app_channel (
    channel                     VARCHAR(32)  NOT NULL,
    current_release_id          BIGINT       NULL,
    min_supported_version_code  INT          NOT NULL DEFAULT 0,
    created_at                  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at                  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (channel)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO rider_app_channel (channel, current_release_id, min_supported_version_code)
VALUES ('production', NULL, 0);

ALTER TABLE rider_device ADD COLUMN app_version_code INT NULL AFTER app_version;
ALTER TABLE rider_device ADD COLUMN managed_mode VARCHAR(32) NULL AFTER app_version_code;
ALTER TABLE rider_device ADD COLUMN last_update_status VARCHAR(32) NULL AFTER managed_mode;
ALTER TABLE rider_device ADD COLUMN last_update_version_code INT NULL AFTER last_update_status;
ALTER TABLE rider_device ADD COLUMN last_update_at DATETIME(6) NULL AFTER last_update_version_code;
