#!/usr/bin/env bash

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "$SCRIPT_DIR/lib/common.sh"

MODE='execute'
if [[ "${1:-}" == "--preflight" ]]; then
  MODE='preflight'
  shift
fi

BUNDLE="${1:-}"
ENV_FILE="${2:-/etc/yulin-youxian/server.env}"
[[ -n "$BUNDLE" ]] || die "usage: $0 [--preflight] <release-directory> [env-file]"
BUNDLE="$(cd "$BUNDLE" && pwd)"

load_env_file "$ENV_FILE"
bash "$SCRIPT_DIR/validate-env.sh" "$ENV_FILE"
verify_release_bundle "$BUNDLE"
validate_release_runtime "$BUNDLE"

for command_name in mysql mysqldump gzip sha256sum pm2; do
  require_command "$command_name"
done

VERSION="$(release_version "$BUNDLE")"
[[ ! -e "$YULIN_ROOT/releases/$VERSION" ]] \
  || die "release $VERSION is already installed; releases are immutable"

MYSQL_PWD="$MYSQL_PASSWORD" mysql \
  --protocol=TCP \
  --host="$MYSQL_HOST" \
  --port="$MYSQL_PORT" \
  --user="$MYSQL_USERNAME" \
  --database="$MYSQL_DATABASE" \
  --batch \
  --skip-column-names \
  --execute='SELECT 1' \
  | { read -r result; [[ "$result" == "1" ]]; } \
  || die "database preflight failed"

if [[ "$MODE" == "preflight" ]]; then
  log "go-live rehearsal passed: env, TLS files, database access and release manifest are valid"
  log "no process, database or symlink was changed"
  exit 0
fi

BACKUP_DIRECTORY="${READINESS_BACKUP_DIRECTORY:-$YULIN_DATA_ROOT/data/backups}"
require_absolute_path BACKUP_DIRECTORY "$BACKUP_DIRECTORY"
install -d -m 0750 "$BACKUP_DIRECTORY"
umask 077

had_process=0
original_release=''
if [[ -L "$YULIN_ROOT/current" ]]; then
  original_release="$(resolved_link_target "$YULIN_ROOT/current")"
fi
if pm2 describe yulin-youxian-server >/dev/null 2>&1; then
  had_process=1
  pm2 stop yulin-youxian-server
fi

restart_original_release() {
  if (( had_process == 1 )) \
    && [[ -n "$original_release" ]] \
    && [[ "$(resolved_link_target "$YULIN_ROOT/current" 2>/dev/null || true)" == "$original_release" ]]; then
    pm2 startOrReload "$YULIN_ROOT/current/ops/pm2/ecosystem.config.cjs" --update-env
    pm2 save
  fi
}

timestamp="$(date '+%Y%m%d-%H%M%S')"
backup_file="$BACKUP_DIRECTORY/pre-deploy-$VERSION-$timestamp.sql.gz"
backup_partial="$backup_file.partial"

log "creating stopped-service MySQL backup"
if ! MYSQL_PWD="$MYSQL_PASSWORD" mysqldump \
  --protocol=TCP \
  --host="$MYSQL_HOST" \
  --port="$MYSQL_PORT" \
  --user="$MYSQL_USERNAME" \
  --single-transaction \
  --quick \
  --routines \
  --triggers \
  --events \
  --set-gtid-purged=OFF \
  "$MYSQL_DATABASE" \
  | gzip -n > "$backup_partial"; then
  rm -f "$backup_partial"
  restart_original_release
  die "database backup failed; old release was restarted when possible"
fi

if ! gzip --test "$backup_partial"; then
  rm -f "$backup_partial"
  restart_original_release
  die "database backup gzip validation failed"
fi
if [[ ! -s "$backup_partial" ]]; then
  rm -f "$backup_partial"
  restart_original_release
  die "database backup is empty"
fi
mv "$backup_partial" "$backup_file"
(
  cd "$BACKUP_DIRECTORY"
  sha256sum "$(basename "$backup_file")" \
    > "$(basename "$backup_file").sha256"
)

log "backup created and checksummed: $backup_file"
if ! bash "$SCRIPT_DIR/install-release.sh" "$BUNDLE" "$ENV_FILE"; then
  restart_original_release
  die "release installation failed; run rollback.sh if current already changed"
fi
log "go-live completed for release $VERSION"
