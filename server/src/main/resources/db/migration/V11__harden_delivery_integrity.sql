CREATE TABLE auth_session (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    subject_type    VARCHAR(16)  NOT NULL COMMENT 'ADMIN/WX/RIDER',
    subject_id      VARCHAR(128) NOT NULL,
    token_hash      BINARY(32)   NOT NULL COMMENT 'SHA-256 原始字节，禁止保存明文令牌',
    issued_at       DATETIME(6)  NOT NULL,
    expires_at      DATETIME(6)  NOT NULL,
    revoked_at      DATETIME(6)  NULL,
    revoke_reason   VARCHAR(128) NULL,
    version         BIGINT       NOT NULL DEFAULT 1,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE UNIQUE INDEX uk_auth_session_token_hash
    ON auth_session (token_hash);

CREATE INDEX idx_auth_session_subject_active
    ON auth_session (subject_type, subject_id, revoked_at, expires_at);

ALTER TABLE delivery_task_event
    ADD COLUMN client_event_id VARCHAR(128) NULL;

ALTER TABLE delivery_task_event
    ADD COLUMN client_action VARCHAR(32) NULL;

-- MYSQL_JSON_BACKFILL_BEGIN
UPDATE delivery_task_event
SET client_event_id = NULLIF(
        NULLIF(JSON_UNQUOTE(JSON_EXTRACT(detail_json, '$.clientEventId')), ''),
        'null'
    ),
    client_action = NULLIF(
        NULLIF(JSON_UNQUOTE(JSON_EXTRACT(detail_json, '$.clientAction')), ''),
        'null'
    )
WHERE detail_json IS NOT NULL
  AND (client_event_id IS NULL OR client_action IS NULL);

-- 旧版只在 JSON 中记录幂等键，异常重试可能已写入同 scope 的重复事件。
-- 保留最早的原始事件；其余事件仍保留审计内容，但不再参与唯一幂等 scope。
UPDATE delivery_task_event duplicate_event
JOIN (
    SELECT client_event_id, task_id, operator_id, client_action, MIN(id) AS keep_id
    FROM delivery_task_event
    WHERE client_event_id IS NOT NULL
      AND client_action IS NOT NULL
      AND operator_id IS NOT NULL
    GROUP BY client_event_id, task_id, operator_id, client_action
    HAVING COUNT(*) > 1
) duplicate_scope
  ON duplicate_scope.client_event_id = duplicate_event.client_event_id
 AND duplicate_scope.task_id = duplicate_event.task_id
 AND duplicate_scope.operator_id = duplicate_event.operator_id
 AND duplicate_scope.client_action = duplicate_event.client_action
SET duplicate_event.client_event_id = NULL,
    duplicate_event.client_action = NULL
WHERE duplicate_event.id <> duplicate_scope.keep_id;
-- MYSQL_JSON_BACKFILL_END

CREATE UNIQUE INDEX uk_event_client_scope
    ON delivery_task_event (client_event_id, task_id, operator_id, client_action);

CREATE INDEX idx_event_outbox
    ON delivery_task_event (event_type, to_status, id);

CREATE INDEX idx_event_task_type
    ON delivery_task_event (task_id, event_type, id);

-- task_id 为 NULL 的人工调整允许并存；有任务归属的规则项只保留一条。
-- 若重复项中已有一条进入结算，优先保留已结算记录，避免破坏历史结算归属。
DELETE FROM delivery_settlement_item
WHERE task_id IS NOT NULL
  AND id NOT IN (
      SELECT keep_id
      FROM (
          SELECT COALESCE(
                     MIN(CASE WHEN settlement_id IS NOT NULL THEN id END),
                     MIN(id)
                 ) AS keep_id
          FROM delivery_settlement_item
          WHERE task_id IS NOT NULL
          GROUP BY task_id, item_type
      ) retained_item
  );

CREATE UNIQUE INDEX uk_item_task_type
    ON delivery_settlement_item (task_id, item_type);

CREATE INDEX idx_exception_resolution
    ON delivery_exception (status, resolution_type, updated_at);

INSERT INTO delivery_config
    (config_key, config_value, value_type, category, display_name, description, min_value, max_value)
VALUES
    ('delivery.family_mode', 'true', 'BOOL', 'DELIVERY', '家庭自营模式',
     '家庭成员自营配送时启用；关闭后按雇佣骑手的疲劳、考核与排班规则执行', NULL, NULL),
    ('delivery.verify_code_ttl_seconds', '7200', 'INT', 'DELIVERY', '送达核销码有效期（秒）',
     '核销码从订单进入可配送状态起的有效时长，超时后需重新获取', '300', '86400'),
    ('routing.amap_max_requests', '64', 'INT', 'ROUTING', '单次规划高德请求上限',
     '限制一次路线规划可发出的高德 Web 服务请求数，超过时回落直线距离估算', '1', '256')
ON DUPLICATE KEY UPDATE
    config_value = VALUES(config_value),
    value_type = VALUES(value_type),
    category = VALUES(category),
    display_name = VALUES(display_name),
    description = VALUES(description),
    min_value = VALUES(min_value),
    max_value = VALUES(max_value);

UPDATE delivery_config
SET display_name = '高德 Web 服务 Key（服务端专用）',
    description = '仅供服务端天气与路径服务使用，不通过配置查询接口回传。AMAP_WEB_KEY 环境变量优先且修改后需重启；未设置环境变量时使用数据库值并支持热更新'
WHERE config_key = 'amap.web_key';
