#!/usr/bin/env bash

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "$SCRIPT_DIR/lib/common.sh"

ENV_FILE="${1:-/etc/yulin-youxian/server.env}"
load_env_file "$ENV_FILE"

required_value() {
  local name="$1"
  local value="${!name:-}"
  [[ -n "$value" ]] || die "$name is required"
  case "$value" in
    __REQUIRED__|CHANGE_ME|changeme|replace-me|REPLACE_ME)
      die "$name still contains a placeholder"
      ;;
  esac
}

required_value TZ
[[ "$TZ" == "Asia/Shanghai" ]] || die "TZ must be Asia/Shanghai"

for name in YULIN_ROOT YULIN_DATA_ROOT YULIN_LOG_ROOT NGINX_CONFIG_PATH TLS_CERTIFICATE TLS_CERTIFICATE_KEY; do
  required_value "$name"
  require_absolute_path "$name" "${!name}"
done

[[ "$YULIN_ROOT" != "$YULIN_DATA_ROOT" ]] || die "release root and data root must be different"

required_value SERVER_PORT
[[ "$SERVER_PORT" =~ ^[0-9]+$ ]] || die "SERVER_PORT must be numeric"
(( SERVER_PORT >= 1024 && SERVER_PORT <= 65535 )) || die "SERVER_PORT is outside 1024-65535"

for name in MYSQL_HOST MYSQL_PORT MYSQL_DATABASE MYSQL_URL MYSQL_USERNAME MYSQL_PASSWORD; do
  required_value "$name"
done
[[ "$MYSQL_PORT" =~ ^[0-9]+$ ]] || die "MYSQL_PORT must be numeric"
[[ "$MYSQL_URL" == jdbc:mysql://* ]] || die "MYSQL_URL must be a MySQL JDBC URL"
[[ "$MYSQL_URL" == "jdbc:mysql://${MYSQL_HOST}:${MYSQL_PORT}/${MYSQL_DATABASE}"* ]] \
  || die "MYSQL_URL must match MYSQL_HOST, MYSQL_PORT and MYSQL_DATABASE"
[[ "$MYSQL_URL" == *"serverTimezone=Asia/Shanghai"* ]] || die "MYSQL_URL must set serverTimezone=Asia/Shanghai"

required_value ADMIN_PASSWORD
if (( ${#ADMIN_PASSWORD} < 12 )); then
  die "ADMIN_PASSWORD must contain at least 12 characters"
fi
case "${ADMIN_PASSWORD,,}" in
  admin@123456|admin123456|password|password123|12345678|qwerty123456)
    die "ADMIN_PASSWORD is a known default or weak password"
    ;;
esac

required_value PUBLIC_HOST
[[ "$PUBLIC_HOST" != *"://"* && "$PUBLIC_HOST" =~ ^[A-Za-z0-9.-]+$ ]] \
  || die "PUBLIC_HOST must be a hostname without a URL scheme"

for name in WECHAT_PAY_NOTIFY_URL WECHAT_PAY_REFUND_NOTIFY_URL; do
  value="${!name:-}"
  [[ -z "$value" || "$value" == https://* ]] || die "$name must use HTTPS in production"
done

if [[ "${DELIVERY_ENABLED:-true}" == "true" ]]; then
  required_value DELIVERY_STORE_LAT
  required_value DELIVERY_STORE_LNG
  [[ "$DELIVERY_STORE_LAT" =~ ^-?[0-9]+([.][0-9]+)?$ ]] || die "DELIVERY_STORE_LAT is not numeric"
  [[ "$DELIVERY_STORE_LNG" =~ ^-?[0-9]+([.][0-9]+)?$ ]] || die "DELIVERY_STORE_LNG is not numeric"
fi

READINESS_BACKUP_DIRECTORY="${READINESS_BACKUP_DIRECTORY:-$YULIN_DATA_ROOT/data/backups}"
require_absolute_path READINESS_BACKUP_DIRECTORY "$READINESS_BACKUP_DIRECTORY"

MAX_BACKUP_AGE_MINUTES="${MAX_BACKUP_AGE_MINUTES:-30}"
MIN_FREE_MB="${MIN_FREE_MB:-5120}"
[[ "$MAX_BACKUP_AGE_MINUTES" =~ ^[0-9]+$ && "$MAX_BACKUP_AGE_MINUTES" -gt 0 ]] \
  || die "MAX_BACKUP_AGE_MINUTES must be a positive integer"
[[ "$MIN_FREE_MB" =~ ^[0-9]+$ && "$MIN_FREE_MB" -gt 0 ]] \
  || die "MIN_FREE_MB must be a positive integer"

[[ -r "$TLS_CERTIFICATE" ]] || die "TLS certificate is not readable: $TLS_CERTIFICATE"
[[ -r "$TLS_CERTIFICATE_KEY" ]] || die "TLS private key is not readable: $TLS_CERTIFICATE_KEY"

log "production environment validation passed"
