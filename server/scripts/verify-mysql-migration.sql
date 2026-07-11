SELECT
    state_key,
    version,
    CHAR_LENGTH(payload) AS json_characters,
    OCTET_LENGTH(payload) AS json_bytes,
    payload_sha256,
    SHA2(CONVERT(payload USING utf8mb4), 256) = payload_sha256 AS checksum_ok,
    migration_source,
    imported_at,
    updated_at
FROM application_state
ORDER BY state_key;

SELECT
    JSON_LENGTH(JSON_EXTRACT(payload, '$.products')) AS products,
    JSON_LENGTH(JSON_EXTRACT(payload, '$.orders')) AS orders,
    JSON_LENGTH(JSON_EXTRACT(payload, '$.refunds')) AS refunds,
    JSON_LENGTH(JSON_EXTRACT(payload, '$.addresses')) AS addresses,
    JSON_LENGTH(JSON_EXTRACT(payload, '$.categories')) AS categories,
    JSON_LENGTH(JSON_EXTRACT(payload, '$.banners')) AS banners
FROM application_state
WHERE state_key = 'storefront';

SELECT
    JSON_LENGTH(JSON_EXTRACT(payload, '$.profiles')) AS user_profiles
FROM application_state
WHERE state_key = 'user-profiles';

SELECT
    @@character_set_database AS database_charset,
    @@collation_database AS database_collation,
    @@character_set_connection AS connection_charset,
    @@collation_connection AS connection_collation;
