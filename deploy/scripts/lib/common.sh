#!/usr/bin/env bash

set -Eeuo pipefail

log() {
  printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S%z')" "$*"
}

die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "required command not found: $1"
}

require_absolute_path() {
  local name="$1"
  local value="$2"
  [[ "$value" == /* ]] || die "$name must be an absolute path: $value"
  [[ "$value" != *$'\n'* ]] || die "$name contains a newline"
}

load_env_file() {
  local env_file="$1"
  [[ -f "$env_file" ]] || die "environment file not found: $env_file"

  if command -v stat >/dev/null 2>&1 && [[ "${ALLOW_INSECURE_ENV_PERMISSIONS:-0}" != "1" ]]; then
    local mode
    mode="$(stat -c '%a' "$env_file")"
    if (( (8#$mode & 077) != 0 )); then
      die "environment file must not be group/world readable (chmod 600): $env_file"
    fi
  fi

  set -a
  # The production env file is administrator-controlled Bash syntax.
  # shellcheck disable=SC1090
  source "$env_file"
  set +a
}

release_version() {
  local bundle="$1"
  [[ -f "$bundle/VERSION" ]] || die "bundle has no VERSION file: $bundle"
  local version
  version="$(tr -d '\r\n' < "$bundle/VERSION")"
  [[ "$version" =~ ^[0-9A-Za-z][0-9A-Za-z._-]{0,63}$ ]] || die "invalid release version: $version"
  printf '%s\n' "$version"
}

verify_release_bundle() {
  local bundle="$1"
  [[ -d "$bundle" ]] || die "release bundle is not a directory: $bundle"
  [[ -f "$bundle/SHA256SUMS" ]] || die "bundle has no SHA256SUMS: $bundle"
  require_command sha256sum
  (
    cd "$bundle"
    sha256sum --check --quiet --strict SHA256SUMS
  )
  release_version "$bundle" >/dev/null
}

validate_release_runtime() {
  local bundle="$1"
  local versions_file="$bundle/ops/versions.env"
  [[ -r "$versions_file" ]] || die "release toolchain metadata is missing: $versions_file"
  (
    # shellcheck disable=SC1090
    source "$versions_file"
    require_command node
    [[ "$(node --version)" == "v$NODE_VERSION" ]] \
      || die "deployment Node must be v$NODE_VERSION; found $(node --version)"
    [[ -x /usr/bin/java ]] || die "/usr/bin/java is required by the PM2 ecosystem"
    local java_output
    java_output="$(/usr/bin/java -version 2>&1)"
    [[ "$java_output" == *"\"$JAVA_VERSION."* || "$java_output" == *"\"$JAVA_VERSION\""* ]] \
      || die "deployment Java must be $JAVA_VERSION"
  )
}

atomic_symlink() {
  local target="$1"
  local link_path="$2"
  local temporary="${link_path}.new.$$"
  ln -s "$target" "$temporary"
  mv -Tf "$temporary" "$link_path"
}

resolved_link_target() {
  local link_path="$1"
  [[ -L "$link_path" ]] || return 1
  readlink -f "$link_path"
}
