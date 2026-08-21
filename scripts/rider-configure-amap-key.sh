#!/usr/bin/env bash
# 把高德 Android Key 写入仓库外的本机发布环境，不进 Git。
set -euo pipefail

ENV_FILE="${RIDER_PUBLISH_ENV:-$HOME/.yulin/rider-publish.env}"
SETUP_FILE="${HOME}/.yulin/AMAP-KEY-SETUP.txt"

usage() {
  cat <<'EOF'
用法:
  scripts/rider-configure-amap-key.sh <高德Android Key>

在高德控制台为 com.yulin.rider 创建 Android Key，SHA-1 必须绑定
~/.yulin/AMAP-KEY-SETUP.txt 里那一行。Web/JS Key 不能用。
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" || $# -lt 1 ]]; then
  usage
  if [[ -f "$SETUP_FILE" ]]; then
    echo
    cat "$SETUP_FILE"
  fi
  exit 0
fi

key="$1"
if [[ ! "$key" =~ ^[A-Za-z0-9]{16,64}$ ]]; then
  echo "高德 Android Key 格式看起来不对，拒绝写入。" >&2
  exit 1
fi
if [[ ! -f "$ENV_FILE" ]]; then
  echo "找不到 $ENV_FILE" >&2
  exit 1
fi

tmp="$(mktemp)"
awk -v key="$key" '
  BEGIN { done=0 }
  /^export AMAP_KEY=/ { print "export AMAP_KEY=" key; done=1; next }
  { print }
  END { if (!done) print "export AMAP_KEY=" key }
' "$ENV_FILE" > "$tmp"
chmod 600 "$tmp"
mv "$tmp" "$ENV_FILE"
chmod 600 "$ENV_FILE"
echo "已写入 $ENV_FILE 的 AMAP_KEY（长度 ${#key}）。"
echo "接下来用 scripts/rider-publish.sh --dry-run 做预检，再打首个新签名包。"
