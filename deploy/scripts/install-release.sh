#!/usr/bin/env bash

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "$SCRIPT_DIR/lib/common.sh"

BUNDLE="${1:-}"
ENV_FILE="${2:-/etc/yulin-youxian/server.env}"
[[ -n "$BUNDLE" ]] || die "usage: $0 <release-directory> [env-file]"
BUNDLE="$(cd "$BUNDLE" && pwd)"

load_env_file "$ENV_FILE"
bash "$SCRIPT_DIR/validate-env.sh" "$ENV_FILE"
verify_release_bundle "$BUNDLE"
validate_release_runtime "$BUNDLE"

for command_name in pm2 install cp mv ln readlink sleep; do
  require_command "$command_name"
done

VERSION="$(release_version "$BUNDLE")"
RELEASES_ROOT="$YULIN_ROOT/releases"
TARGET_RELEASE="$RELEASES_ROOT/$VERSION"
CURRENT_LINK="$YULIN_ROOT/current"
PREVIOUS_LINK="$YULIN_ROOT/previous"

[[ ! -e "$TARGET_RELEASE" && ! -L "$TARGET_RELEASE" ]] \
  || die "release already exists and will not be overwritten: $TARGET_RELEASE"

install -d -m 0755 "$YULIN_ROOT" "$RELEASES_ROOT"
install -d -m 0750 \
  "$YULIN_DATA_ROOT" \
  "$YULIN_DATA_ROOT/data" \
  "$YULIN_DATA_ROOT/data/uploads" \
  "$YULIN_DATA_ROOT/data/uploads/delivery" \
  "$YULIN_DATA_ROOT/data/backups" \
  "$YULIN_LOG_ROOT"

STAGING="$RELEASES_ROOT/.$VERSION.staging.$$"
trap 'rm -rf "$STAGING"' EXIT
mkdir "$STAGING"
cp -a "$BUNDLE/." "$STAGING/"
verify_release_bundle "$STAGING"
chmod -R a-w "$STAGING"
mv "$STAGING" "$TARGET_RELEASE"
trap - EXIT

old_release=''
if [[ -L "$CURRENT_LINK" ]]; then
  old_release="$(resolved_link_target "$CURRENT_LINK")"
  [[ "$old_release" == "$RELEASES_ROOT/"* ]] \
    || die "current points outside the managed release root: $old_release"
  verify_release_bundle "$old_release"
  atomic_symlink "$old_release" "$PREVIOUS_LINK"
elif [[ -e "$CURRENT_LINK" ]]; then
  die "current exists but is not a symlink: $CURRENT_LINK"
elif [[ -L "$PREVIOUS_LINK" ]]; then
  rm -f "$PREVIOUS_LINK"
fi

atomic_symlink "$TARGET_RELEASE" "$CURRENT_LINK"
log "current switched to immutable release $VERSION"

pm2 startOrReload "$CURRENT_LINK/ops/pm2/ecosystem.config.cjs" --update-env
pm2 save
bash "$CURRENT_LINK/ops/scripts/render-nginx.sh" "$ENV_FILE"

ready=0
for attempt in {1..18}; do
  if bash "$CURRENT_LINK/ops/scripts/readiness.sh" "$ENV_FILE"; then
    ready=1
    break
  fi
  log "readiness attempt $attempt/18 failed; retrying in 10 seconds"
  sleep 10
done

if (( ready != 1 )); then
  die "release $VERSION failed readiness; inspect logs and run rollback.sh to return to N-1"
fi

log "release $VERSION is live and PM2 state was saved"
