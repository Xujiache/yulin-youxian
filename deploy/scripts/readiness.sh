#!/usr/bin/env bash

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "$SCRIPT_DIR/lib/common.sh"

if [[ $# -gt 0 ]]; then
  load_env_file "$1"
fi

: "${TZ:=Asia/Shanghai}"
: "${YULIN_ROOT:=/opt/yulin-youxian}"
: "${YULIN_DATA_ROOT:=/var/lib/yulin-youxian}"
: "${SERVER_PORT:=8080}"
: "${MYSQL_HOST:=127.0.0.1}"
: "${MYSQL_PORT:=3306}"
: "${MYSQL_DATABASE:=yulin_fresh}"
: "${MYSQL_USERNAME:=yulin_fresh}"
: "${MYSQL_PASSWORD:?MYSQL_PASSWORD is required}"
: "${READINESS_BACKUP_DIRECTORY:=$YULIN_DATA_ROOT/data/backups}"
: "${MAX_BACKUP_AGE_MINUTES:=30}"
: "${MIN_FREE_MB:=5120}"

export TZ

for command_name in mysql sha256sum df stat curl mktemp awk; do
  require_command "$command_name"
done

require_absolute_path YULIN_ROOT "$YULIN_ROOT"
require_absolute_path YULIN_DATA_ROOT "$YULIN_DATA_ROOT"
require_absolute_path READINESS_BACKUP_DIRECTORY "$READINESS_BACKUP_DIRECTORY"
[[ "$TZ" == "Asia/Shanghai" ]] || die "readiness requires TZ=Asia/Shanghai"

CURRENT_RELEASE="$YULIN_ROOT/current"
[[ -L "$CURRENT_RELEASE" ]] || die "current is not a release symlink: $CURRENT_RELEASE"
[[ -r "$CURRENT_RELEASE/server/fresh-delivery-server.jar" ]] || die "current backend jar is missing"
[[ -r "$CURRENT_RELEASE/admin/dist/index.html" ]] || die "current admin dist is missing"
[[ -r "$CURRENT_RELEASE/SHA256SUMS" ]] || die "current release manifest is missing"
(
  cd "$CURRENT_RELEASE"
  sha256sum --check --quiet --strict SHA256SUMS
) || die "current release SHA256 manifest verification failed"

SQL_FILE=''
for candidate in \
  "$SCRIPT_DIR/../sql/readiness.sql" \
  "$SCRIPT_DIR/../../server/scripts/readiness.sql"; do
  if [[ -r "$candidate" ]]; then
    SQL_FILE="$candidate"
    break
  fi
done
[[ -n "$SQL_FILE" ]] || die "readiness.sql was not found"

db_status="$(
  MYSQL_PWD="$MYSQL_PASSWORD" mysql \
    --protocol=TCP \
    --host="$MYSQL_HOST" \
    --port="$MYSQL_PORT" \
    --user="$MYSQL_USERNAME" \
    --database="$MYSQL_DATABASE" \
    --batch \
    --skip-column-names \
    < "$SQL_FILE"
)" || die "database/Flyway readiness query failed"
[[ "$db_status" == "READY" ]] || die "database is not ready: $db_status"

probe_directory() {
  local directory="$1"
  [[ -d "$directory" ]] || die "required storage directory is missing: $directory"
  [[ -w "$directory" ]] || die "required storage directory is not writable: $directory"
  local probe
  probe="$(mktemp "$directory/.readiness.XXXXXX")" || die "cannot create storage probe in $directory"
  printf 'readiness\n' > "$probe"
  rm -f "$probe"
}

probe_directory "$YULIN_DATA_ROOT/data"
probe_directory "$YULIN_DATA_ROOT/data/uploads"
probe_directory "$READINESS_BACKUP_DIRECTORY"

available_kb="$(df -Pk "$YULIN_DATA_ROOT" | awk 'NR == 2 { print $4 }')"
[[ "$available_kb" =~ ^[0-9]+$ ]] || die "could not determine free disk space"
available_mb=$(( available_kb / 1024 ))
(( available_mb >= MIN_FREE_MB )) \
  || die "free disk is ${available_mb}MB; at least ${MIN_FREE_MB}MB is required"

shopt -s nullglob
backup_files=(
  "$READINESS_BACKUP_DIRECTORY"/backup-*.zip
  "$READINESS_BACKUP_DIRECTORY"/pre-deploy-*.sql
  "$READINESS_BACKUP_DIRECTORY"/pre-deploy-*.sql.gz
)
shopt -u nullglob
(( ${#backup_files[@]} > 0 )) || die "no operational backup was found in $READINESS_BACKUP_DIRECTORY"

latest_backup_epoch=0
latest_backup=''
for backup_file in "${backup_files[@]}"; do
  modified_epoch="$(stat -c '%Y' "$backup_file")"
  if (( modified_epoch > latest_backup_epoch )); then
    latest_backup_epoch="$modified_epoch"
    latest_backup="$backup_file"
  fi
done

now_epoch="$(date +%s)"
(( latest_backup_epoch <= now_epoch + 300 )) || die "latest backup timestamp is in the future: $latest_backup"
backup_age_minutes=$(( (now_epoch - latest_backup_epoch) / 60 ))
(( backup_age_minutes <= MAX_BACKUP_AGE_MINUTES )) \
  || die "latest backup is ${backup_age_minutes} minutes old; limit is ${MAX_BACKUP_AGE_MINUTES}"

health_json="$(curl --fail --silent --show-error \
  --max-time 10 \
  "http://127.0.0.1:${SERVER_PORT}/actuator/health")" \
  || die "loopback Actuator health request failed"
[[ "$health_json" == *'"status":"UP"'* ]] || die "Actuator is not UP: $health_json"

log "READY: release manifest, Flyway baseline, business tables, storage, disk, backup age and Actuator passed"
