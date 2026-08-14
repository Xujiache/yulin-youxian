SET @schema_name := DATABASE();

SELECT COUNT(*) INTO @flyway_latest
FROM flyway_schema_history
WHERE version = '19' AND success = 1;

SELECT COUNT(*) INTO @flyway_failed
FROM flyway_schema_history
WHERE success <> 1;

SELECT COALESCE(MAX(CAST(version AS UNSIGNED)), 0) INTO @flyway_max_version
FROM flyway_schema_history
WHERE version IS NOT NULL;

SELECT COUNT(*) INTO @business_table_count
FROM information_schema.tables
WHERE table_schema = @schema_name
  AND table_type = 'BASE TABLE'
  AND table_name <> 'flyway_schema_history';

SELECT COUNT(*) INTO @expected_table_count
FROM information_schema.tables
WHERE table_schema = @schema_name
  AND table_type = 'BASE TABLE'
  AND table_name IN (
    'application_state',
    'auth_session',
    'rider',
    'rider_session',
    'rider_device',
    'rider_shift',
    'delivery_task',
    'delivery_task_event',
    'delivery_wave',
    'delivery_wave_stop',
    'rider_location',
    'rider_location_latest',
    'delivery_geofence_event',
    'delivery_exception',
    'delivery_evidence',
    'delivery_weight_check',
    'delivery_settlement',
    'delivery_settlement_item',
    'rider_score_event',
    'rider_appeal',
    'route_plan',
    'distance_matrix_cache',
    'building_handoff_stat',
    'geo_poi_cache',
    'delivery_config',
    'delivery_zone',
    'rider_message',
    'privacy_number_binding',
    'delivery_rating',
    'marketing_lottery_campaign',
    'marketing_lottery_tier',
    'marketing_lottery_prize',
    'marketing_lottery_challenge',
    'marketing_lottery_draw',
    'marketing_lottery_gift',
    'marketing_lottery_order_decision',
    'marketing_lottery_draw_guard',
    'delivery_route_plan_failure'
  );

SELECT IF(
  @flyway_latest = 1
  AND @flyway_failed = 0
  AND @flyway_max_version = 19
  AND @business_table_count = 38
  AND @expected_table_count = 38,
  'READY',
  CONCAT(
    'NOT_READY',
    ' flyway_latest=', @flyway_latest,
    ' flyway_failed=', @flyway_failed,
    ' flyway_max=', @flyway_max_version,
    ' business_tables=', @business_table_count,
    ' expected_tables=', @expected_table_count
  )
) AS readiness;
