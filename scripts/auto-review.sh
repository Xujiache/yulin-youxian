#!/usr/bin/env bash
# 三端自动审查循环：审查 -> 自动修复 -> 提交推送 -> 等待 -> 下一轮。
#
# 每一轮做的事（与 CI 的检查口径一致）：
#   1. git pull --rebase 拉取远端新提交（循环的长期价值就在这里：
#      任何人推了代码，最迟一轮之内就会被完整审查一遍）
#   2. 后端    server/mvnw clean test          （512 个测试）
#   3. 后台    eslint --fix 自动修复，再复查必须为零问题
#   4. 后台    vite build + vue-tsc 构建与类型检查
#   5. 骑手端  gradle testDebugUnitTest lintDebug
#   6. 全仓库  密钥与归档扫描（.github/scripts/audit_tracked_files.py）
#   7. 把本轮结果追加到 docs/auto-review/review-log.md，连同自动修复
#      一起提交并推送 —— 每一轮都有提交，日志就是审查凭证
#
# 停止方式：touch scripts/auto-review.stop（当前轮跑完后退出），或直接 kill。
# 可调参数（环境变量）：
#   SLEEP_SECONDS   两轮之间的等待，默认 60
#   MAX_ROUNDS      跑满多少轮后退出，默认 0 = 不限
#
# 依赖：JDK 17、Node 22.14.0 + npm 10.9.2、Android SDK。脚本会按常见路径
# 自动探测；探测不到就用当前 PATH 里的版本并在日志里注明。

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
LOG_FILE="$REPO_ROOT/docs/auto-review/review-log.md"
STOP_FILE="$SCRIPT_DIR/auto-review.stop"
STATE_DIR="${TMPDIR:-/tmp}/auto-review-state"
SLEEP_SECONDS="${SLEEP_SECONDS:-60}"
MAX_ROUNDS="${MAX_ROUNDS:-0}"

mkdir -p "$STATE_DIR" "$(dirname "$LOG_FILE")"

# ---------- 环境探测 ----------
if [[ -z "${JAVA_HOME:-}" ]]; then
  for candidate in /usr/lib/jvm/java-17-openjdk-amd64 /usr/lib/jvm/temurin-17-jdk-amd64; do
    [[ -x "$candidate/bin/java" ]] && export JAVA_HOME="$candidate" && break
  done
fi
[[ -n "${JAVA_HOME:-}" ]] && export PATH="$JAVA_HOME/bin:$PATH"

if [[ -x "$HOME/.nvm/versions/node/v22.14.0/bin/node" ]]; then
  export PATH="$HOME/.nvm/versions/node/v22.14.0/bin:$PATH"
fi

if [[ -z "${ANDROID_HOME:-}" ]]; then
  for candidate in "$HOME/android-sdk" "$HOME/Android/Sdk" "$HOME/Library/Android/sdk"; do
    [[ -d "$candidate/platforms" ]] && export ANDROID_HOME="$candidate" && break
  done
fi
export ANDROID_SDK_ROOT="${ANDROID_HOME:-}"

# ---------- 工具函数 ----------
ts() { date -u '+%Y-%m-%d %H:%M:%S UTC'; }

# run_step <名称> <日志片段文件> <超时秒> <命令...>
# 返回命令退出码；stdout/stderr 全部落到独立文件便于失败时摘录。
run_step() {
  local name="$1" out="$2" limit="$3"; shift 3
  local started ended rc
  started=$(date +%s)
  timeout "$limit" "$@" > "$out" 2>&1
  rc=$?
  ended=$(date +%s)
  STEP_SECONDS=$((ended - started))
  if [[ $rc -eq 124 ]]; then
    echo "（超时 ${limit}s 被终止）" >> "$out"
  fi
  return $rc
}

# 失败时取输出末尾几行进日志，成功时只记录耗时
summarize() {
  local rc="$1" out="$2"
  if [[ $rc -eq 0 ]]; then
    echo "通过，${STEP_SECONDS}s"
  else
    echo "**失败（退出码 $rc，${STEP_SECONDS}s）**，末尾输出："
    echo '```'
    tail -5 "$out" | sed 's/^/    /'
    echo '```'
  fi
}

git_push_with_retry() {
  local attempt
  for attempt in 1 2 3 4; do
    git -C "$REPO_ROOT" push && return 0
    # 远端在本轮期间又前进了：拉下来再推，下一轮会审查这些新提交
    git -C "$REPO_ROOT" pull --rebase --autostash || git -C "$REPO_ROOT" rebase --abort
    sleep $((attempt * 4))
  done
  return 1
}

# ---------- 单轮审查 ----------
review_round() {
  local round="$1"
  local entry="$STATE_DIR/entry.md"
  local failures=0 fixes=""

  : > "$entry"
  echo "## 第 ${round} 轮 · $(ts)" >> "$entry"

  # 1. 同步远端
  local pull_out="$STATE_DIR/pull.log"
  if git -C "$REPO_ROOT" pull --rebase --autostash > "$pull_out" 2>&1; then
    if grep -q "Fast-forward\|Successfully rebased" "$pull_out" 2>/dev/null; then
      echo "- 拉取远端：有新提交，本轮将连同审查" >> "$entry"
    else
      echo "- 拉取远端：无新内容" >> "$entry"
    fi
  else
    git -C "$REPO_ROOT" rebase --abort 2>/dev/null
    echo "- 拉取远端：**失败（可能有冲突），本轮只审查本地内容**" >> "$entry"
  fi

  # 2. 后端测试
  local rc
  run_step backend "$STATE_DIR/backend.log" 900 \
    bash -c "cd '$REPO_ROOT/server' && ./mvnw -B -ntp clean test"
  rc=$?
  echo "- 后端 mvn clean test：$(summarize $rc "$STATE_DIR/backend.log")" >> "$entry"
  [[ $rc -ne 0 ]] && failures=$((failures + 1))

  # 3. 后台 eslint 自动修复 + 复查
  run_step eslint-fix "$STATE_DIR/eslint-fix.log" 600 \
    bash -c "cd '$REPO_ROOT/art-lnb-master' && npx --no-install eslint . --fix"
  if ! git -C "$REPO_ROOT" diff --quiet -- art-lnb-master; then
    local changed
    changed=$(git -C "$REPO_ROOT" diff --name-only -- art-lnb-master | wc -l)
    fixes="${fixes}eslint 自动修复 ${changed} 个文件；"
    echo "- 后台 eslint --fix：自动修复了 ${changed} 个文件" >> "$entry"
  else
    echo "- 后台 eslint --fix：无可修复项" >> "$entry"
  fi
  run_step eslint "$STATE_DIR/eslint.log" 600 \
    bash -c "cd '$REPO_ROOT/art-lnb-master' && npx --no-install eslint ."
  rc=$?
  echo "- 后台 eslint 复查：$(summarize $rc "$STATE_DIR/eslint.log")" >> "$entry"
  [[ $rc -ne 0 ]] && failures=$((failures + 1))

  # 4. 后台构建 + 类型检查（lockfile 变了才重新 npm ci）
  local lock_hash stored=""
  lock_hash=$(sha256sum "$REPO_ROOT/art-lnb-master/package-lock.json" | cut -d' ' -f1)
  [[ -f "$STATE_DIR/lock.hash" ]] && stored=$(cat "$STATE_DIR/lock.hash")
  if [[ "$lock_hash" != "$stored" || ! -d "$REPO_ROOT/art-lnb-master/node_modules" ]]; then
    run_step npm-ci "$STATE_DIR/npm-ci.log" 900 \
      bash -c "cd '$REPO_ROOT/art-lnb-master' && npm ci"
    rc=$?
    echo "- 后台 npm ci（lockfile 有变化）：$(summarize $rc "$STATE_DIR/npm-ci.log")" >> "$entry"
    [[ $rc -eq 0 ]] && echo "$lock_hash" > "$STATE_DIR/lock.hash" || failures=$((failures + 1))
  fi
  run_step admin-build "$STATE_DIR/admin-build.log" 900 \
    bash -c "cd '$REPO_ROOT/art-lnb-master' && npm run build"
  rc=$?
  echo "- 后台 vite build + vue-tsc：$(summarize $rc "$STATE_DIR/admin-build.log")" >> "$entry"
  [[ $rc -ne 0 ]] && failures=$((failures + 1))

  # 5. 骑手端单测 + lint（保留 daemon，循环里增量很快）
  run_step rider "$STATE_DIR/rider.log" 1800 \
    bash -c "cd '$REPO_ROOT/rider-android' && ./gradlew testDebugUnitTest lintDebug"
  rc=$?
  echo "- 骑手端 testDebugUnitTest + lintDebug：$(summarize $rc "$STATE_DIR/rider.log")" >> "$entry"
  [[ $rc -ne 0 ]] && failures=$((failures + 1))

  # 6. 密钥扫描
  run_step secrets "$STATE_DIR/secrets.log" 300 \
    bash -c "cd '$REPO_ROOT' && python3 .github/scripts/audit_tracked_files.py"
  rc=$?
  echo "- 密钥与归档扫描：$(summarize $rc "$STATE_DIR/secrets.log")" >> "$entry"
  [[ $rc -ne 0 ]] && failures=$((failures + 1))

  # 7. 结论 + 提交推送
  local verdict
  if [[ $failures -eq 0 && -z "$fixes" ]]; then
    verdict="全部通过，无需改动"
  elif [[ $failures -eq 0 ]]; then
    verdict="发现问题并已自动修复（${fixes%；}）"
  else
    verdict="**有 ${failures} 项未通过，需要人工跟进**${fixes:+；已自动修复：${fixes%；}}"
  fi
  echo "- 结论：${verdict}" >> "$entry"
  echo >> "$entry"

  if [[ ! -f "$LOG_FILE" ]]; then
    {
      echo "# 自动审查日志"
      echo
      echo "由 scripts/auto-review.sh 生成。每一轮：拉取远端 -> 三端全量检查 ->"
      echo "eslint 自动修复 -> 记录本文件 -> 提交推送。最新一轮在最上面。"
      echo
    } > "$LOG_FILE"
  fi
  # 新轮次插到文件头部（标题四行之后），最近的审查一眼可见
  local tmp="$STATE_DIR/log.tmp"
  head -5 "$LOG_FILE" > "$tmp"
  cat "$entry" >> "$tmp"
  tail -n +6 "$LOG_FILE" >> "$tmp"
  mv "$tmp" "$LOG_FILE"

  git -C "$REPO_ROOT" add -A
  git -C "$REPO_ROOT" commit -q -m "chore(review): 第 ${round} 轮自动审查 — ${verdict//\*\*/}" || true
  if git_push_with_retry > "$STATE_DIR/push.log" 2>&1; then
    echo "[$(ts)] 第 ${round} 轮完成并已推送：${verdict//\*\*/}"
  else
    echo "[$(ts)] 第 ${round} 轮完成但推送失败，改动已在本地提交，下一轮会重试"
  fi
  return $failures
}

# ---------- 主循环 ----------
round=$(( $(grep -c '^## 第' "$LOG_FILE" 2>/dev/null || echo 0) + 1 ))
echo "[$(ts)] 自动审查循环启动，起始轮次 ${round}，轮间隔 ${SLEEP_SECONDS}s，停止方式：touch $STOP_FILE"

while true; do
  review_round "$round"
  round=$((round + 1))

  if [[ -f "$STOP_FILE" ]]; then
    echo "[$(ts)] 检测到停止标记，退出"
    rm -f "$STOP_FILE"
    break
  fi
  if [[ "$MAX_ROUNDS" != "0" && $round -gt "$MAX_ROUNDS" ]]; then
    echo "[$(ts)] 已达到 MAX_ROUNDS=$MAX_ROUNDS，退出"
    break
  fi
  sleep "$SLEEP_SECONDS"
done
