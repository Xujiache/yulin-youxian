#!/usr/bin/env bash
# 本机骑手 APK 直发：测试、签名、验签、上传 localhost 内部接口、核对 OTA。
# 只接受已提交的 HEAD；未跟踪文件不阻断。密钥读仓库外 ~/.yulin/rider-publish.env。
set -euo pipefail

usage() {
  cat <<'EOF'
用法:
  scripts/rider-publish.sh --title "标题" --notes "更新日志" [选项]

选项:
  --title TEXT          骑手看到的更新标题（发布时必填）
  --notes TEXT          更新日志（发布时必填）
  --policy OPTIONAL|FORCE   默认 OPTIONAL。FORCE 会阻断所有更旧客户端
  --sequence N          当日序号 1-99；默认按后端当前版本递增
  --abi both|arm64      默认 both（arm64-v8a + armeabi-v7a）
  --dry-run | --preflight  只检查 SDK/签名/版本，不构建
  --build-only          构建并验签，不上传、不改 OTA 指针
  --skip-tests          仅应急；正式发布不要用
  --allow-empty-amap    允许在没有高德 Android Key 时发布（地图会空白）
  --sync-pm2-env        只把发布令牌和证书摘要注入当前 PM2 并 pm2 save
  -h, --help

环境文件默认 $HOME/.yulin/rider-publish.env（chmod 600）。
EOF
}

TITLE=""
NOTES=""
POLICY="OPTIONAL"
SEQUENCE=""
ABI="both"
DRY_RUN=0
BUILD_ONLY=0
SKIP_TESTS=0
ALLOW_EMPTY_AMAP=0
SYNC_PM2_ONLY=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --title) TITLE="${2:-}"; shift 2 ;;
    --notes) NOTES="${2:-}"; shift 2 ;;
    --policy) POLICY="${2:-}"; shift 2 ;;
    --sequence) SEQUENCE="${2:-}"; shift 2 ;;
    --abi) ABI="${2:-}"; shift 2 ;;
    --dry-run|--preflight) DRY_RUN=1; shift ;;
    --build-only) BUILD_ONLY=1; shift ;;
    --skip-tests) SKIP_TESTS=1; shift ;;
    --allow-empty-amap) ALLOW_EMPTY_AMAP=1; shift ;;
    --sync-pm2-env) SYNC_PM2_ONLY=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "未知参数: $1" >&2; usage >&2; exit 2 ;;
  esac
done

POLICY="${POLICY^^}"
if [[ "$POLICY" != "OPTIONAL" && "$POLICY" != "FORCE" ]]; then
  echo "policy 只能是 OPTIONAL 或 FORCE" >&2
  exit 2
fi
if [[ "$ABI" != "both" && "$ABI" != "arm64" ]]; then
  echo "abi 只能是 both 或 arm64" >&2
  exit 2
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ENV_FILE="${RIDER_PUBLISH_ENV:-$HOME/.yulin/rider-publish.env}"
RECEIPT_DIR="${RIDER_PUBLISH_RECEIPT_DIR:-$HOME/.yulin/releases}"
PM2_BIN="${PM2_BIN:-/opt/node/bin/pm2}"
export PM2_HOME="${PM2_HOME:-$HOME/.pm2}"

log() { printf '[rider-publish] %s\n' "$*"; }
die() { printf '[rider-publish] %s\n' "$*" >&2; exit 1; }

load_publish_env() {
  [[ -f "$ENV_FILE" ]] || die "找不到发布环境文件: $ENV_FILE"
  # shellcheck disable=SC1090
  set -a
  source "$ENV_FILE"
  set +a
  export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-/opt/android-sdk}"
  export ANDROID_HOME="${ANDROID_HOME:-$ANDROID_SDK_ROOT}"
  export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
  export PATH="$JAVA_HOME/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH"
}

latest_build_tools() {
  local dir=""
  dir="$(find "$ANDROID_SDK_ROOT/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
  [[ -n "$dir" ]] || die "未找到 build-tools"
  printf '%s' "$dir"
}

sync_pm2_env() {
  load_publish_env
  [[ -n "${RIDER_APP_PUBLISH_TOKEN:-}" ]] || die "RIDER_APP_PUBLISH_TOKEN 为空"
  [[ -n "${RIDER_EXPECTED_CERT_SHA256:-}" ]] || die "RIDER_EXPECTED_CERT_SHA256 为空"
  command -v "$PM2_BIN" >/dev/null || die "找不到 pm2: $PM2_BIN"
  # --update-env 会把当前 shell 环境合并进进程。先清掉签名口令，避免私钥进 JVM。
  unset RIDER_KEYSTORE_PASSWORD RIDER_KEY_PASSWORD RIDER_KEYSTORE_PATH RIDER_KEY_ALIAS
  export RIDER_APP_PUBLISH_TOKEN RIDER_EXPECTED_CERT_SHA256
  "$PM2_BIN" restart yulin-youxian-server --update-env
  "$PM2_BIN" save
  log "已把发布令牌和证书摘要写入当前 PM2 进程并保存 dump。"
}

if [[ "$SYNC_PM2_ONLY" -eq 1 ]]; then
  sync_pm2_env
  exit 0
fi

load_publish_env

if [[ "$DRY_RUN" -eq 0 && "$BUILD_ONLY" -eq 0 ]]; then
  [[ -n "$TITLE" ]] || die "发布需要 --title"
  [[ -n "$NOTES" ]] || die "发布需要 --notes"
fi

cd "$REPO_ROOT"
git rev-parse --is-inside-work-tree >/dev/null || die "不在 git 仓库内"
SOURCE_SHA="$(git rev-parse HEAD)"
git update-index -q --refresh
if ! git diff-files --quiet -- || ! git diff-index --quiet --cached HEAD --; then
  if [[ "$DRY_RUN" -eq 1 || "$BUILD_ONLY" -eq 1 ]]; then
    log "警告: 存在已跟踪文件的未提交改动。预检/只构建可以继续，正式发布会被拒绝。"
  else
    die "存在已跟踪文件的未提交改动。发布只接受已提交的 HEAD（未跟踪文件可忽略）。"
  fi
fi

BUILD_TOOLS="$(latest_build_tools)"
AAPT2="$BUILD_TOOLS/aapt2"
APKSIGNER="$BUILD_TOOLS/apksigner"
[[ -x "$AAPT2" ]] || die "找不到 aapt2: $AAPT2"
[[ -x "$APKSIGNER" ]] || die "找不到 apksigner: $APKSIGNER"
[[ -f "${RIDER_KEYSTORE_PATH:-}" ]] || die "找不到 keystore: ${RIDER_KEYSTORE_PATH:-未配置}"
[[ -n "${RIDER_EXPECTED_CERT_SHA256:-}" ]] || die "RIDER_EXPECTED_CERT_SHA256 未配置"
[[ -x "$REPO_ROOT/rider-android/gradlew" ]] || die "找不到 rider-android/gradlew"

if [[ -z "${AMAP_KEY:-}" ]]; then
  if [[ "$DRY_RUN" -eq 1 || "$BUILD_ONLY" -eq 1 || "$ALLOW_EMPTY_AMAP" -eq 1 ]]; then
    log "警告: AMAP_KEY 为空。地图底图会空白。用 scripts/rider-configure-amap-key.sh 写入。"
  else
    die "未配置 AMAP_KEY。先在高德控制台绑定新证书 SHA-1，再执行 scripts/rider-configure-amap-key.sh <Key>。预检/只构建可用 --dry-run / --build-only。"
  fi
fi

PUBLISH_BASE="${RIDER_PUBLISH_BASE_URL:-http://127.0.0.1:8090}"
PUBLISH_BASE="${PUBLISH_BASE%/}"

fetch_latest_code() {
  local payload
  payload="$(curl -fsS --max-time 15 \
    "${PUBLISH_BASE}/api/public/rider/app/latest?channel=production&versionCode=0" || true)"
  if [[ -z "$payload" ]]; then
    printf '%s' ""
    return 0
  fi
  python3 -c 'import json,sys
p=json.loads(sys.stdin.read())
d=p.get("data") or {}
c=d.get("versionCode")
print("" if c in (None,"") else int(c))' <<<"$payload"
}

compute_version() {
  local current="${1:-0}"
  python3 - "$current" "${SEQUENCE:-}" <<'PY'
import datetime, sys
from zoneinfo import ZoneInfo
current = int(sys.argv[1] or 0)
requested = sys.argv[2].strip()
now = datetime.datetime.now(ZoneInfo("Asia/Shanghai"))
today = int(now.strftime("%y%m%d"))
name_prefix = now.strftime("%Y.%m.%d")
if requested:
    seq = int(requested)
else:
    if current // 100 == today:
        seq = (current % 100) + 1
    elif current // 100 > today:
        raise SystemExit(f"后端当前 versionCode={current} 已超过今天 {today}，拒绝降级。检查服务器时区或显式传 --sequence。")
    else:
        seq = 1
if seq < 1 or seq > 99:
    raise SystemExit(f"当日序号越界: {seq}")
version_code = today * 100 + seq
if current and version_code <= current:
    raise SystemExit(f"计算出的 versionCode={version_code} 不高于后端当前 {current}")
print(f"{version_code}\t{name_prefix}.{seq}\t{seq}")
PY
}

CURRENT_CODE="$(fetch_latest_code || true)"
CURRENT_CODE="${CURRENT_CODE:-0}"
VERSION_LINE="$(compute_version "${CURRENT_CODE:-0}")"
VERSION_CODE="$(printf '%s' "$VERSION_LINE" | cut -f1)"
VERSION_NAME="$(printf '%s' "$VERSION_LINE" | cut -f2)"
RESOLVED_SEQ="$(printf '%s' "$VERSION_LINE" | cut -f3)"

log "HEAD=$SOURCE_SHA"
log "后端当前 versionCode=${CURRENT_CODE:-0}"
log "本次 versionName=$VERSION_NAME versionCode=$VERSION_CODE policy=$POLICY abi=$ABI"

if [[ "$DRY_RUN" -eq 1 ]]; then
  log "preflight 通过（未构建、未发布）。"
  exit 0
fi

cd "$REPO_ROOT/rider-android"
chmod +x gradlew
GRADLE_ARGS=(--no-daemon)
if [[ "$SKIP_TESTS" -eq 0 ]]; then
  GRADLE_ARGS+=(testDebugUnitTest)
fi
GRADLE_ARGS+=(
  :app:verifyReleaseSignature
  "-PRIDER_VERSION_CODE=$VERSION_CODE"
  "-PRIDER_VERSION_NAME=$VERSION_NAME"
  "-PRIDER_BUILD_SEQUENCE=$RESOLVED_SEQ"
  "-PRIDER_EXPECTED_CERT_SHA256=$RIDER_EXPECTED_CERT_SHA256"
)
if [[ -n "${AMAP_KEY:-}" ]]; then
  GRADLE_ARGS+=("-PAMAP_KEY=$AMAP_KEY")
fi
if [[ "$ABI" == "arm64" ]]; then
  GRADLE_ARGS+=("-PRIDER_ABI=arm64")
fi

log "开始 Gradle: ${GRADLE_ARGS[*]}"
./gradlew "${GRADLE_ARGS[@]}"

APK="$REPO_ROOT/rider-android/app/build/outputs/apk/release/app-release.apk"
[[ -f "$APK" ]] || die "未找到 $APK"

BADGING="$("$AAPT2" dump badging "$APK")"
PACKAGE="$(printf '%s\n' "$BADGING" | sed -n "s/^package: name='\([^']*\)'.*/\1/p" | head -n1)"
APK_CODE="$(printf '%s\n' "$BADGING" | sed -n "s/.*versionCode='\([^']*\)'.*/\1/p" | head -n1)"
APK_NAME="$(printf '%s\n' "$BADGING" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -n1)"
NATIVE="$(printf '%s\n' "$BADGING" | sed -n "s/native-code: //p" | head -n1)"
[[ "$PACKAGE" == "com.yulin.rider" ]] || die "包名不是 com.yulin.rider: $PACKAGE"
[[ "$APK_CODE" == "$VERSION_CODE" ]] || die "APK versionCode=$APK_CODE 与预期 $VERSION_CODE 不一致"
[[ "$APK_NAME" == "$VERSION_NAME" ]] || die "APK versionName=$APK_NAME 与预期 $VERSION_NAME 不一致"
if [[ "$ABI" == "arm64" ]]; then
  printf '%s' "$NATIVE" | grep -q 'arm64-v8a' || die "缺少 arm64-v8a: $NATIVE"
  if printf '%s' "$NATIVE" | grep -q 'armeabi-v7a'; then
    die "arm64 小包不应包含 armeabi-v7a: $NATIVE"
  fi
else
  printf '%s' "$NATIVE" | grep -q 'arm64-v8a' || die "缺少 arm64-v8a: $NATIVE"
  printf '%s' "$NATIVE" | grep -q 'armeabi-v7a' || die "缺少 armeabi-v7a: $NATIVE"
fi

SIGN_OUT="$("$APKSIGNER" verify --verbose --print-certs "$APK")"
printf '%s\n' "$SIGN_OUT" | grep -q 'Verified using v2 scheme' || die "缺少 v2 签名"
CERT_LINE="$(printf '%s\n' "$SIGN_OUT" | grep -i 'SHA-256 digest:' | head -n1 || true)"
CERT_NORM="$(printf '%s' "$CERT_LINE" | awk -F: '{print $NF}' | tr -d ' :' | tr 'A-F' 'a-f')"
EXPECTED_NORM="$(printf '%s' "$RIDER_EXPECTED_CERT_SHA256" | tr -d ' :' | tr 'A-F' 'a-f')"
[[ "$CERT_NORM" == "$EXPECTED_NORM" ]] || die "证书 SHA-256 与钉扎值不一致"

APK_SHA="$(sha256sum "$APK" | awk '{print $1}')"
APK_SIZE="$(stat -c '%s' "$APK")"
log "验签通过 $PACKAGE $APK_NAME ($APK_CODE) ${NATIVE}"
log "APK SHA-256=$APK_SHA size=$APK_SIZE"

mkdir -p "$RECEIPT_DIR"
chmod 700 "$RECEIPT_DIR"
RECEIPT="$RECEIPT_DIR/${VERSION_NAME}.json"

if [[ "$BUILD_ONLY" -eq 1 ]]; then
  python3 - "$RECEIPT" <<PY
import json, sys
json.dump({
  "status": "built-not-published",
  "versionName": "$VERSION_NAME",
  "versionCode": int("$VERSION_CODE"),
  "sourceSha": "$SOURCE_SHA",
  "apk": "$APK",
  "apkSha256": "$APK_SHA",
  "apkSize": int("$APK_SIZE"),
  "certSha256": "$EXPECTED_NORM",
  "packageName": "$PACKAGE",
  "abi": "$ABI",
  "policy": "$POLICY",
}, open(sys.argv[1], "w"), ensure_ascii=False, indent=2)
print()
PY
  chmod 600 "$RECEIPT"
  log "已构建但未发布。回执: $RECEIPT"
  log "APK: $APK"
  exit 0
fi

[[ -n "${RIDER_APP_PUBLISH_TOKEN:-}" ]] || die "RIDER_APP_PUBLISH_TOKEN 为空，无法上传"
curl -fsS --max-time 10 "${PUBLISH_BASE}/actuator/health" >/dev/null \
  || die "后端健康检查失败: ${PUBLISH_BASE}/actuator/health"

RESPONSE="$(mktemp)"
trap 'rm -f "$RESPONSE"' EXIT
HTTP_CODE="$(
  curl -sS --max-time 600 -o "$RESPONSE" -w '%{http_code}' \
    -X POST \
    -H "X-Rider-App-Publish-Token: ${RIDER_APP_PUBLISH_TOKEN}" \
    -F "file=@${APK};type=application/vnd.android.package-archive" \
    -F "channel=production" \
    -F "title=${TITLE}" \
    -F "notes=${NOTES}" \
    -F "policy=${POLICY}" \
    -F "sourceSha=${SOURCE_SHA}" \
    -F "operator=server-local" \
    "${PUBLISH_BASE}/api/internal/rider-app/releases"
)"
if [[ "$HTTP_CODE" != "200" ]]; then
  die "发布接口 HTTP $HTTP_CODE: $(head -c 500 "$RESPONSE")"
fi

python3 - "$RESPONSE" "$VERSION_CODE" "$APK_SHA" <<'PY'
import json, sys
payload = json.load(open(sys.argv[1], encoding="utf-8"))
if payload.get("code") not in (0, "0"):
    raise SystemExit(f"publish rejected: {payload}")
data = payload.get("data") or {}
if int(data.get("versionCode") or 0) != int(sys.argv[2]):
    raise SystemExit(f"published versionCode {data.get('versionCode')} != {sys.argv[2]}")
if (data.get("fileSha256") or "").lower() != sys.argv[3].lower():
    raise SystemExit("published fileSha256 mismatch")
if data.get("publishedBy") != "server-local":
    raise SystemExit(f"publishedBy={data.get('publishedBy')} 应为 server-local")
if data.get("status") != "PUBLISHED":
    raise SystemExit(f"status={data.get('status')}")
print(f"published id={data.get('id')} versionCode={data.get('versionCode')} publishedBy={data.get('publishedBy')}")
PY

LATEST="$(mktemp)"
trap 'rm -f "$RESPONSE" "$LATEST"' EXIT
curl -fsS --max-time 30 -o "$LATEST" \
  "${PUBLISH_BASE}/api/public/rider/app/latest?channel=production&versionCode=0"
DOWNLOAD_URL="$(python3 - "$LATEST" "$VERSION_CODE" "$APK_SHA" "$PUBLISH_BASE" <<'PY'
import json, sys
from urllib.parse import urlparse
payload = json.load(open(sys.argv[1], encoding="utf-8"))
if payload.get("code") not in (0, "0"):
    raise SystemExit(f"latest rejected: {payload}")
data = payload.get("data") or {}
if int(data.get("versionCode") or 0) != int(sys.argv[2]):
    raise SystemExit(f"latest versionCode {data.get('versionCode')} != {sys.argv[2]}")
if (data.get("fileSha256") or "").lower() != sys.argv[3].lower():
    raise SystemExit("latest fileSha256 mismatch")
url = data.get("fileUrl") or ""
if not url:
    raise SystemExit("latest fileUrl empty")
base = sys.argv[4].rstrip("/")
parsed = urlparse(url)
path = parsed.path if parsed.scheme else url
if not path.startswith("/"):
    path = "/" + path
print(base + path)
PY
)"

RANGE_HDR="$(mktemp)"
RANGE_BODY="$(mktemp)"
FULL_BODY="$(mktemp)"
trap 'rm -f "$RESPONSE" "$LATEST" "$RANGE_HDR" "$RANGE_BODY" "$FULL_BODY"' EXIT
RANGE_CODE="$(curl -sS --max-time 60 -D "$RANGE_HDR" -o "$RANGE_BODY" -w '%{http_code}' \
  -H 'Range: bytes=0-1023' "$DOWNLOAD_URL")"
[[ "$RANGE_CODE" == "206" ]] || die "Range 下载应返回 206，实际 $RANGE_CODE ($DOWNLOAD_URL)"
[[ "$(stat -c '%s' "$RANGE_BODY")" -eq 1024 ]] || die "Range 正文长度不是 1024"

curl -fsS --max-time 600 -o "$FULL_BODY" "$DOWNLOAD_URL"
DL_SHA="$(sha256sum "$FULL_BODY" | awk '{print $1}')"
[[ "$DL_SHA" == "$APK_SHA" ]] || die "下载哈希 $DL_SHA 与 APK $APK_SHA 不一致"
[[ "$(stat -c '%s' "$FULL_BODY")" == "$APK_SIZE" ]] || die "下载大小与 APK 不一致"

TITLE="$TITLE" NOTES="$NOTES" DOWNLOAD_URL="$DOWNLOAD_URL" python3 - "$RECEIPT" <<PY
import json, os, sys
json.dump({
  "status": "published",
  "versionName": "$VERSION_NAME",
  "versionCode": int("$VERSION_CODE"),
  "sourceSha": "$SOURCE_SHA",
  "apk": "$APK",
  "apkSha256": "$APK_SHA",
  "apkSize": int("$APK_SIZE"),
  "certSha256": "$EXPECTED_NORM",
  "packageName": "$PACKAGE",
  "abi": "$ABI",
  "policy": "$POLICY",
  "title": os.environ.get("TITLE", ""),
  "notes": os.environ.get("NOTES", ""),
  "operator": "server-local",
  "downloadUrl": os.environ.get("DOWNLOAD_URL", ""),
}, open(sys.argv[1], "w"), ensure_ascii=False, indent=2)
PY
chmod 600 "$RECEIPT"
log "发布完成。回执: $RECEIPT"
log "latest: $DOWNLOAD_URL"
log "APK: $APK"
