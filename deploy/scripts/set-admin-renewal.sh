#!/usr/bin/env bash
set -euo pipefail

[[ $# -ge 1 && $# -le 2 ]] || { printf 'expected on/off and optional release root\n' >&2; exit 2; }
case "${1:-}" in
  on) enabled=true ;;
  off) enabled=false ;;
  *) printf 'usage: %s <on|off> [absolute-release-root]\n' "$0" >&2; exit 2 ;;
esac

root="${2:-${YULIN_ROOT:-/opt/yulin-youxian}}"
[[ "$root" = /* && "$root" != / ]] || { printf 'invalid release root\n' >&2; exit 2; }
root="$(realpath -m -- "$root")"
[[ "$root" != / ]] || { printf 'invalid release root\n' >&2; exit 2; }
directory="$root/runtime"
file="$directory/admin-renewal.json"
[[ ! -L "$directory" && ! -L "$file" ]] || { printf 'unexpected runtime symlink\n' >&2; exit 2; }
install -d -m 0755 "$directory"
candidate="$(mktemp "$directory/.admin-renewal.XXXXXX")"
trap 'rm -f -- "$candidate"' EXIT
if [[ -f "$file" ]]; then
  cp -p -- "$file" "$file.before.$(date -u +%Y%m%dT%H%M%S%N)"
fi
printf '{"enabled":%s}\n' "$enabled" > "$candidate"
chmod 0644 "$candidate"
mv -fT -- "$candidate" "$file"
trap - EXIT
printf 'admin renewal notice: %s\n' "$enabled"
