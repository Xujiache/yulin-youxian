#!/usr/bin/env bash

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "$SCRIPT_DIR/lib/common.sh"

BACKUP_FILE="${1:-}"
ENV_FILE="${2:-/etc/yulin-youxian/server.env}"
[[ -n "$BACKUP_FILE" ]] || die "usage: $0 <mysqldump.sql|mysqldump.sql.gz> [env-file]"
[[ -r "$BACKUP_FILE" ]] || die "backup is not readable: $BACKUP_FILE"
BACKUP_FILE="$(cd "$(dirname "$BACKUP_FILE")" && pwd)/$(basename "$BACKUP_FILE")"

load_env_file "$ENV_FILE"
bash "$SCRIPT_DIR/validate-env.sh" "$ENV_FILE"

: "${MYSQL_ADMIN_USER:=root}"
: "${MYSQL_ADMIN_PASSWORD:?set MYSQL_ADMIN_PASSWORD for the isolated restore drill}"
for command_name in mysql sha256sum gzip; do
  require_command "$command_name"
done

checksum_file="$BACKUP_FILE.sha256"
[[ -f "$checksum_file" ]] || die "adjacent backup checksum is required: $checksum_file"
(
  cd "$(dirname "$BACKUP_FILE")"
  sha256sum --check --strict "$(basename "$checksum_file")"
)

case "$BACKUP_FILE" in
  *.sql|*.sql.gz) ;;
  *) die "restore drill accepts only .sql or .sql.gz backups" ;;
esac

DRILL_DATABASE="restore_drill_$(date '+%Y%m%d_%H%M%S')_$$"
[[ "$DRILL_DATABASE" != "$MYSQL_DATABASE" ]] || die "refusing to restore over the production database"

admin_mysql() {
  MYSQL_PWD="$MYSQL_ADMIN_PASSWORD" mysql \
    --protocol=TCP \
    --host="$MYSQL_HOST" \
    --port="$MYSQL_PORT" \
    --user="$MYSQL_ADMIN_USER" \
    "$@"
}

cleanup() {
  if [[ "${KEEP_DRILL_DB:-0}" != "1" ]]; then
    admin_mysql --execute="DROP DATABASE IF EXISTS \`$DRILL_DATABASE\`" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

admin_mysql --execute="CREATE DATABASE \`$DRILL_DATABASE\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
log "restoring backup into isolated database $DRILL_DATABASE"

if [[ "$BACKUP_FILE" == *.gz ]]; then
  gzip --decompress --stdout "$BACKUP_FILE" \
    | MYSQL_PWD="$MYSQL_ADMIN_PASSWORD" mysql \
        --protocol=TCP \
        --host="$MYSQL_HOST" \
        --port="$MYSQL_PORT" \
        --user="$MYSQL_ADMIN_USER" \
        "$DRILL_DATABASE"
else
  MYSQL_PWD="$MYSQL_ADMIN_PASSWORD" mysql \
    --protocol=TCP \
    --host="$MYSQL_HOST" \
    --port="$MYSQL_PORT" \
    --user="$MYSQL_ADMIN_USER" \
    "$DRILL_DATABASE" \
    < "$BACKUP_FILE"
fi

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

restore_status="$(
  MYSQL_PWD="$MYSQL_ADMIN_PASSWORD" mysql \
    --protocol=TCP \
    --host="$MYSQL_HOST" \
    --port="$MYSQL_PORT" \
    --user="$MYSQL_ADMIN_USER" \
    --database="$DRILL_DATABASE" \
    --batch \
    --skip-column-names \
    < "$SQL_FILE"
)"
[[ "$restore_status" == "READY" ]] || die "restored database failed validation: $restore_status"

if [[ "${KEEP_DRILL_DB:-0}" == "1" ]]; then
  log "restore drill passed; KEEP_DRILL_DB=1 left $DRILL_DATABASE for manual inspection"
else
  cleanup
  trap - EXIT
  log "restore drill passed: Flyway baseline and all business tables verified; isolated database removed"
fi
