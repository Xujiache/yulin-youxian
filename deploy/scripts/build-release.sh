#!/usr/bin/env bash

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
# shellcheck source=lib/common.sh
source "$SCRIPT_DIR/lib/common.sh"
# shellcheck source=../versions.env
source "$REPO_ROOT/deploy/versions.env"

VERSION="${1:-}"
OUTPUT_ROOT="${2:-$REPO_ROOT/deploy/out}"
[[ -n "$VERSION" ]] || die "usage: $0 <version> [output-root]"
[[ "$VERSION" =~ ^[0-9A-Za-z][0-9A-Za-z._-]{0,63}$ ]] || die "invalid version: $VERSION"

for command_name in git java node npm sha256sum sort xargs tar gzip; do
  require_command "$command_name"
done

actual_node_version="$(node --version)"
[[ "$actual_node_version" == "v$NODE_VERSION" ]] \
  || die "Node must be v$NODE_VERSION; found $actual_node_version"
actual_npm_version="$(npm --version)"
[[ "$actual_npm_version" == "$NPM_VERSION" ]] \
  || die "npm must be $NPM_VERSION; found $actual_npm_version"
java_version_output="$(java -version 2>&1)"
[[ "$java_version_output" == *"\"$JAVA_VERSION."* || "$java_version_output" == *"\"$JAVA_VERSION\""* ]] \
  || die "Java $JAVA_VERSION is required"

[[ -f "$REPO_ROOT/art-lnb-master/package-lock.json" ]] || die "admin package-lock.json is required"
[[ -x "$REPO_ROOT/server/mvnw" ]] || die "server Maven wrapper is missing or not executable"

# 骑手端 APK 默认不打：服务器发布机通常没有 Android SDK，更不该放签名 keystore。
# 要连 APK 一起出就 RIDER_APK=1，前置条件缺一不可，缺了直接失败而不是悄悄跳过。
# 这一段只把签名包打进 tarball，不会登记 OTA，也不是正式骑手发布入口。
# 正式发布用 scripts/rider-publish.sh。
# 半个包比没有包更危险。
BUILD_RIDER_APK="${RIDER_APK:-0}"
if [[ "$BUILD_RIDER_APK" == "1" ]]; then
  [[ -x "$REPO_ROOT/rider-android/gradlew" ]] || die "rider Gradle wrapper is missing or not executable"
  [[ -f "$REPO_ROOT/rider-android/gradle.properties" ]] \
    || die "rider-android/gradle.properties is missing; it is tracked in Git, restore it with: git checkout -- rider-android/gradle.properties"
  [[ -n "${ANDROID_HOME:-}${ANDROID_SDK_ROOT:-}" ]] \
    || die "ANDROID_HOME (or ANDROID_SDK_ROOT) must point at an Android SDK to build the rider APK"
fi

if [[ "${ALLOW_DIRTY_BUILD:-0}" != "1" ]]; then
  dirty_status="$(git -C "$REPO_ROOT" status --porcelain --untracked-files=normal)"
  [[ -z "$dirty_status" ]] || die "release builds require a clean Git worktree (set ALLOW_DIRTY_BUILD=1 only for local rehearsal)"
fi

mkdir -p "$OUTPUT_ROOT"
OUTPUT_ROOT="$(cd "$OUTPUT_ROOT" && pwd)"
RELEASE_DIR="$OUTPUT_ROOT/$VERSION"
ARCHIVE_PATH="$OUTPUT_ROOT/yulin-youxian-$VERSION.tar.gz"
[[ ! -e "$RELEASE_DIR" ]] || die "release directory already exists and will not be overwritten: $RELEASE_DIR"
[[ ! -e "$ARCHIVE_PATH" ]] || die "release archive already exists and will not be overwritten: $ARCHIVE_PATH"

STAGING="$OUTPUT_ROOT/.$VERSION.staging.$$"
trap 'rm -rf "$STAGING"' EXIT
mkdir -p "$STAGING/release/server" "$STAGING/release/admin" "$STAGING/release/ops"

SOURCE_SHA="${SOURCE_SHA:-$(git -C "$REPO_ROOT" rev-parse HEAD)}"
SOURCE_DATE_EPOCH="${SOURCE_DATE_EPOCH:-$(git -C "$REPO_ROOT" show -s --format=%ct "$SOURCE_SHA")}"
[[ "$SOURCE_DATE_EPOCH" =~ ^[0-9]+$ ]] || die "SOURCE_DATE_EPOCH must be numeric"
export SOURCE_DATE_EPOCH

log "building backend from $SOURCE_SHA"
(
  cd "$REPO_ROOT/server"
  ./mvnw \
    -B \
    -ntp \
    -DskipTests \
    -Dbuild.dir="$STAGING/server-target" \
    -Dproject.build.outputTimestamp="$SOURCE_DATE_EPOCH" \
    clean package
)

shopt -s nullglob
backend_jars=("$STAGING"/server-target/fresh-delivery-server-*.jar)
shopt -u nullglob
(( ${#backend_jars[@]} == 1 )) || die "expected exactly one backend jar; found ${#backend_jars[@]}"
cp "${backend_jars[0]}" "$STAGING/release/server/fresh-delivery-server.jar"

log "building admin with locked Node and npm versions"
(
  cd "$REPO_ROOT/art-lnb-master"
  HUSKY=0 npm ci --ignore-scripts=false
  # 必须先 build 再 typecheck：ref/computed/ElMessage 这些全局符号的类型声明
  # (src/types/import/*.d.ts) 由 unplugin-auto-import 在构建时生成，且按上游模板的做法
  # 不入库。先跑 vue-tsc 的话，全新 clone 上这些名字全都「找不到」，几百条误报。
  # 顺序调换不影响门禁：类型不过照样在这里失败，只是多花一次构建的时间。
  npx --no-install vite build \
    --outDir "$STAGING/admin-dist" \
    --emptyOutDir
  npx --no-install vue-tsc --noEmit
)
mv "$STAGING/admin-dist" "$STAGING/release/admin/dist"

if [[ "$BUILD_RIDER_APK" == "1" ]]; then
  log "building signed rider APK"
  (
    cd "$REPO_ROOT/rider-android"
    # assembleRelease 在签名材料不全时会在配置阶段直接失败，不会产出未签名包，
    # 所以这里不需要再自己校验一遍 keystore。
    # 不要试图用 -PbuildDir 把各模块产物挪到 staging：多模块共用一个 buildDir 会让
    # 任务输出互相覆盖，Gradle 直接以 implicit dependency 报错。就地构建再拷出来。
    # RIDER_ABI=arm64 只打 64 位包，体积从 ~100MB 降到 ~70MB。
    # 现在的手机基本都是 arm64，只有极老的 32 位机型需要默认的双 ABI。
    rider_gradle_args=()
    [[ -n "${RIDER_ABI:-}" ]] && rider_gradle_args+=("-PRIDER_ABI=$RIDER_ABI")
    ./gradlew --no-daemon "${rider_gradle_args[@]}" \
      clean testDebugUnitTest lintDebug assembleRelease verifyReleaseSignature
  )
  shopt -s nullglob
  rider_apks=("$REPO_ROOT"/rider-android/app/build/outputs/apk/release/*.apk)
  shopt -u nullglob
  (( ${#rider_apks[@]} == 1 )) || die "expected exactly one rider release APK; found ${#rider_apks[@]}"
  mkdir -p "$STAGING/release/rider"
  cp "${rider_apks[0]}" "$STAGING/release/rider/yulin-rider-$VERSION.apk"
fi

cp -a "$REPO_ROOT/deploy/pm2" "$STAGING/release/ops/"
cp -a "$REPO_ROOT/deploy/nginx" "$STAGING/release/ops/"
cp -a "$REPO_ROOT/deploy/scripts" "$STAGING/release/ops/"
cp "$REPO_ROOT/deploy/versions.env" "$STAGING/release/ops/versions.env"
mkdir -p "$STAGING/release/ops/sql"
cp "$REPO_ROOT/server/scripts/readiness.sql" "$STAGING/release/ops/sql/readiness.sql"

printf '%s\n' "$VERSION" > "$STAGING/release/VERSION"
cat > "$STAGING/release/BUILD-METADATA" <<EOF
version=$VERSION
source_sha=$SOURCE_SHA
source_date_epoch=$SOURCE_DATE_EPOCH
java_version=$JAVA_VERSION
node_version=$NODE_VERSION
npm_version=$NPM_VERSION
flyway_version=$EXPECTED_FLYWAY_VERSION
business_tables=$EXPECTED_BUSINESS_TABLES
rider_apk=$BUILD_RIDER_APK
EOF

MANIFEST_TMP="$STAGING/SHA256SUMS"
(
  cd "$STAGING/release"
  shopt -s globstar nullglob dotglob
  files=()
  for file in **/*; do
    [[ -f "$file" ]] && files+=("$file")
  done
  (( ${#files[@]} > 0 ))
  printf '%s\0' "${files[@]}" | LC_ALL=C sort -z | xargs -0 sha256sum
) > "$MANIFEST_TMP"
mv "$MANIFEST_TMP" "$STAGING/release/SHA256SUMS"
(
  cd "$STAGING/release"
  sha256sum --check --quiet --strict SHA256SUMS
)

rm -rf "$STAGING/server-target"
mv "$STAGING/release" "$RELEASE_DIR"

ARCHIVE_TMP="$ARCHIVE_PATH.tmp.$$"
tar \
  --sort=name \
  --mtime="@$SOURCE_DATE_EPOCH" \
  --owner=0 \
  --group=0 \
  --numeric-owner \
  -C "$OUTPUT_ROOT" \
  -cf - "$VERSION" \
  | gzip -n > "$ARCHIVE_TMP"
mv "$ARCHIVE_TMP" "$ARCHIVE_PATH"
(
  cd "$OUTPUT_ROOT"
  sha256sum "$(basename "$ARCHIVE_PATH")" \
    > "$(basename "$ARCHIVE_PATH").sha256"
)

trap - EXIT
rmdir "$STAGING"
log "immutable release created: $RELEASE_DIR"
log "reproducible archive created: $ARCHIVE_PATH"
