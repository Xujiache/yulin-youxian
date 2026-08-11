#!/usr/bin/env bash

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "$SCRIPT_DIR/lib/common.sh"

MODE='execute'
if [[ "${1:-}" == "--check" ]]; then
  MODE='check'
  shift
fi
ENV_FILE="${1:-/etc/yulin-youxian/server.env}"

load_env_file "$ENV_FILE"
bash "$SCRIPT_DIR/validate-env.sh" "$ENV_FILE"
require_command pm2

CURRENT_LINK="$YULIN_ROOT/current"
PREVIOUS_LINK="$YULIN_ROOT/previous"
RELEASES_ROOT="$YULIN_ROOT/releases"

current_release="$(resolved_link_target "$CURRENT_LINK")" \
  || die "current release symlink is missing"
previous_release="$(resolved_link_target "$PREVIOUS_LINK")" \
  || die "N-1 previous release symlink is missing"

[[ "$current_release" == "$RELEASES_ROOT/"* ]] || die "current points outside managed releases"
[[ "$previous_release" == "$RELEASES_ROOT/"* ]] || die "previous points outside managed releases"
[[ "$current_release" != "$previous_release" ]] || die "current and previous point to the same release"

verify_release_bundle "$current_release"
verify_release_bundle "$previous_release"
validate_release_runtime "$previous_release"

current_version="$(release_version "$current_release")"
previous_version="$(release_version "$previous_release")"

if [[ "$MODE" == "check" ]]; then
  log "rollback rehearsal passed: $current_version -> $previous_version"
  log "both manifests are valid; no symlink or process was changed"
  exit 0
fi

atomic_symlink "$previous_release" "$CURRENT_LINK"
atomic_symlink "$current_release" "$PREVIOUS_LINK"
log "current switched from $current_version to N-1 $previous_version"

pm2 startOrReload "$CURRENT_LINK/ops/pm2/ecosystem.config.cjs" --update-env
pm2 save
bash "$CURRENT_LINK/ops/scripts/render-nginx.sh" "$ENV_FILE"

ready=0
for attempt in {1..12}; do
  if bash "$CURRENT_LINK/ops/scripts/readiness.sh" "$ENV_FILE"; then
    ready=1
    break
  fi
  log "rollback readiness attempt $attempt/12 failed; retrying in 10 seconds"
  sleep 10
done
(( ready == 1 )) || die "N-1 release failed readiness; escalate and do not alter database automatically"

log "rollback completed; database schema was intentionally left at its forward version"
