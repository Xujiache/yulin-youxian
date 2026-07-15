CREATE TABLE IF NOT EXISTS application_state (
    state_key VARCHAR(64) NOT NULL,
    payload LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 1,
    migration_source VARCHAR(512) NULL,
    imported_at DATETIME(6) NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (state_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
