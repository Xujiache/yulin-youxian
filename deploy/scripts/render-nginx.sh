#!/usr/bin/env bash

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "$SCRIPT_DIR/lib/common.sh"

ENV_FILE="${1:-/etc/yulin-youxian/server.env}"
load_env_file "$ENV_FILE"
bash "$SCRIPT_DIR/validate-env.sh" "$ENV_FILE"

for command_name in envsubst nginx install; do
  require_command "$command_name"
done

TEMPLATE="${2:-$YULIN_ROOT/current/ops/nginx/yulin-youxian.conf.template}"
[[ -r "$TEMPLATE" ]] || die "Nginx template is not readable: $TEMPLATE"

CONFIG_DIRECTORY="$(dirname "$NGINX_CONFIG_PATH")"
[[ -d "$CONFIG_DIRECTORY" ]] || die "Nginx config directory does not exist: $CONFIG_DIRECTORY"

CANDIDATE="$CONFIG_DIRECTORY/.yulin-youxian.conf.candidate.$$"
BACKUP="$CONFIG_DIRECTORY/.yulin-youxian.conf.previous.$$"
trap 'rm -f "$CANDIDATE" "$BACKUP"' EXIT

envsubst '${PUBLIC_HOST} ${TLS_CERTIFICATE} ${TLS_CERTIFICATE_KEY} ${SERVER_PORT} ${YULIN_ROOT}' \
  < "$TEMPLATE" > "$CANDIDATE"
[[ -s "$CANDIDATE" ]] || die "rendered Nginx config is empty"

had_previous=0
if [[ -f "$NGINX_CONFIG_PATH" ]]; then
  cp -a "$NGINX_CONFIG_PATH" "$BACKUP"
  had_previous=1
fi
install -m 0644 "$CANDIDATE" "$NGINX_CONFIG_PATH"

if ! nginx -t; then
  if (( had_previous == 1 )); then
    mv -f "$BACKUP" "$NGINX_CONFIG_PATH"
  else
    rm -f "$NGINX_CONFIG_PATH"
  fi
  die "Nginx validation failed; previous config restored"
fi

if ! nginx -s reload; then
  if (( had_previous == 1 )); then
    mv -f "$BACKUP" "$NGINX_CONFIG_PATH"
    nginx -t && nginx -s reload || true
  else
    rm -f "$NGINX_CONFIG_PATH"
  fi
  die "Nginx reload failed; previous config restored when available"
fi
rm -f "$BACKUP"
trap - EXIT
rm -f "$CANDIDATE"
log "Nginx config rendered, validated and reloaded"
