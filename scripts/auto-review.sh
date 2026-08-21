#!/usr/bin/env bash
# 三端自动审查循环：同步远端 -> 全量审查 -> 自动修复 -> 提交推送 -> 等待 -> 下一轮。
#
# 每一轮做的事（检查口径对齐 .github/workflows/ci.yml 的全部任务）：
#   1. 与远端同步，自动处理分叉与冲突（策略见 sync_with_remote 注释）；
#      基线 main 有新内容时并进来一起审查；出现新分支时在日志里点名
#   2. 后端      server/mvnw clean test
#   3. 后台      eslint --fix 自动修复，复查必须归零；vite build + vue-tsc
#   4. 骑手端    gradle testDebugUnitTest lintDebug
#   5. 小程序    node .github/scripts/check-miniprogram.mjs client-wechat
#   6. 运维模板  deploy 脚本 bash -n + PM2 配置 node --check + 发布契约检查
#   7. 全仓库    密钥与归档扫描
#   （打印代理只能在 Windows 上编译，本机跳过；若本轮拉到 print-agent 改动会在日志里标注）
#   8. 结果写入 docs/auto-review/review-log.md，连同自动修复一起提交推送
#
# 同步策略（无人值守的前提是永远不能卡死在冲突上）：
#   本分支：
#     仅远端领先      -> fast-forward
#     仅本地领先      -> 不动，轮末推送
#     双方分叉        -> 先试 rebase（历史保持线性）
#       rebase 冲突   -> 放弃 rebase，改 merge -X theirs（冲突块以远端为准；
#                        本地未推送的内容基本是审查日志与 eslint 修复，本轮会自动重做）
#       merge 也失败  -> 本地提交备份到 review-backup-<时间戳> 分支，
#                        工作区硬重置到远端，日志里大写标注 —— 绝不丢内容，也绝不停摆
#   基线 main（新项目可能直接传到 main）：
#     main 有本分支缺的提交 -> 普通合并进来一起审查；
#     合并冲突 -> 放弃并标红留给人工 —— main 侧冲突意味着两边改了同一处业务，
#     机器不该替人裁决，这一点与本分支冲突「以远端为准」刻意不同
#   新分支（新项目也可能另开分支上传）：
#     每轮对比远端分支清单，新出现的分支在日志里点名并给出领先提交数，
#     不自动并入 —— 是否纳入主线由人决定
#   审查日志本身在 .gitattributes 里配了 merge=union，两边的轮次记录都会保留。
#
# 停止方式：touch scripts/auto-review.stop（当前轮跑完后退出），或直接 kill。
# 可调参数（环境变量）：
#   SLEEP_SECONDS   两轮之间的等待，默认 60
#   MAX_ROUNDS      跑满多少轮后退出，默认 0 = 不限
#   BASE_BRANCH     基线分支，默认 main
#
# 依赖：JDK 17、Node 22.14.0 + npm 10.9.2、Android SDK。脚本会按常见路径
# 自动探测；探测不到就用当前 PATH 里的版本并在日志里注明。

set -u

# 整个循环不允许任何子进程读终端：gradle 客户端会把 TTY 当 stdin 去探测，
# 在 tmux 里作为后台进程组读终端会被内核 SIGTTIN 挂起，整轮审查就此卡死。
exec < /dev/null

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
LOG_FILE="$REPO_ROOT/docs/auto-review/review-log.md"
STOP_FILE="$SCRIPT_DIR/auto-review.stop"
STATE_DIR="${TMPDIR:-/tmp}/auto-review-state"
SLEEP_SECONDS="${SLEEP_SECONDS:-60}"
MAX_ROUNDS="${MAX_ROUNDS:-0}"
BASE_BRANCH="${BASE_BRANCH:-main}"
BRANCH="$(git -C "$REPO_ROOT" rev-parse --abbrev-ref HEAD)"

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

run_step() {
  local name="$1" out="$2" limit="$3"; shift 3
  local started ended rc
  started=$(date +%s)
  timeout "$limit" "$@" > "$out" 2>&1
  rc=$?
  ended=$(date +%s)
  STEP_SECONDS=$((ended - started))
  [[ $rc -eq 124 ]] && echo "（超时 ${limit}s 被终止）" >> "$out"
  return $rc
}

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

# 清掉上一轮可能留下的半截 rebase/merge，保证本轮从干净状态开始
abort_in_progress_ops() {
  local git_dir
  git_dir="$(git -C "$REPO_ROOT" rev-parse --git-dir)"
  [[ -d "$git_dir/rebase-merge" || -d "$git_dir/rebase-apply" ]] \
    && git -C "$REPO_ROOT" rebase --abort >/dev/null 2>&1
  [[ -f "$git_dir/MERGE_HEAD" ]] \
    && git -C "$REPO_ROOT" merge --abort >/dev/null 2>&1
  [[ -f "$git_dir/CHERRY_PICK_HEAD" ]] \
    && git -C "$REPO_ROOT" cherry-pick --abort >/dev/null 2>&1
  return 0
}

# 与远端同分支同步。结果写入全局 SYNC_NOTE / SYNC_OLD_HEAD。
sync_with_remote() {
  SYNC_OLD_HEAD="$(git -C "$REPO_ROOT" rev-parse HEAD)"
  SYNC_NOTE="拉取远端：网络失败，本轮只审查本地内容"

  abort_in_progress_ops
  git -C "$REPO_ROOT" fetch origin "$BRANCH" > "$STATE_DIR/fetch.log" 2>&1 || return 0

  local local_head remote_head base ahead behind
  local_head="$(git -C "$REPO_ROOT" rev-parse HEAD)"
  remote_head="$(git -C "$REPO_ROOT" rev-parse "origin/$BRANCH")"
  base="$(git -C "$REPO_ROOT" merge-base HEAD "origin/$BRANCH")"
  ahead="$(git -C "$REPO_ROOT" rev-list --count "origin/$BRANCH..HEAD")"
  behind="$(git -C "$REPO_ROOT" rev-list --count "HEAD..origin/$BRANCH")"

  if [[ "$remote_head" == "$local_head" ]]; then
    SYNC_NOTE="拉取远端：无新内容"
  elif [[ "$base" == "$local_head" ]]; then
    git -C "$REPO_ROOT" merge --ff-only "origin/$BRANCH" >/dev/null 2>&1 \
      && SYNC_NOTE="拉取远端：快进合入 ${behind} 个新提交" \
      || SYNC_NOTE="拉取远端：**快进失败，本轮只审查本地内容**"
  elif [[ "$base" == "$remote_head" ]]; then
    SYNC_NOTE="拉取远端：无新内容（本地领先 ${ahead} 个提交，轮末推送）"
  else
    # 双方分叉。先 rebase 保线性，冲突就退回 merge，合并里冲突块以远端为准。
    if git -C "$REPO_ROOT" rebase "origin/$BRANCH" > "$STATE_DIR/rebase.log" 2>&1; then
      SYNC_NOTE="拉取远端：双方分叉（本地 ${ahead} / 远端 ${behind}），已变基保持线性"
    else
      git -C "$REPO_ROOT" rebase --abort >/dev/null 2>&1
      if git -C "$REPO_ROOT" merge -X theirs --no-edit "origin/$BRANCH" \
           > "$STATE_DIR/merge.log" 2>&1; then
        SYNC_NOTE="拉取远端：**变基有冲突，已改用合并收拢（冲突块以远端为准），本轮全量检查会复核结果**"
      else
        git -C "$REPO_ROOT" merge --abort >/dev/null 2>&1
        local backup="review-backup-$(date -u +%Y%m%d-%H%M%S)"
        git -C "$REPO_ROOT" branch "$backup" >/dev/null 2>&1
        git -C "$REPO_ROOT" reset --hard "origin/$BRANCH" >/dev/null 2>&1
        SYNC_NOTE="拉取远端：**自动合并失败。本地提交已备份到分支 ${backup}，工作区已重置到远端，需要人工合并该备份分支**"
      fi
    fi
  fi
  return 0
}

# 基线 main 有新内容（例如新项目直接传到 main）时并进来一起审查。
# 与本分支冲突时不自动裁决：main 侧冲突意味着两边改了同一处业务，
# 机器不该替人挑边 —— 这与同分支同步「以远端为准」刻意不同。
sync_with_base() {
  MAIN_NOTE=""
  [[ "$BRANCH" == "$BASE_BRANCH" ]] && return 0
  git -C "$REPO_ROOT" fetch origin "$BASE_BRANCH" > /dev/null 2>&1 || return 0
  git -C "$REPO_ROOT" merge-base --is-ancestor "origin/$BASE_BRANCH" HEAD 2>/dev/null && return 0

  local incoming
  incoming="$(git -C "$REPO_ROOT" rev-list --count "HEAD..origin/$BASE_BRANCH")"
  if git -C "$REPO_ROOT" merge --no-edit "origin/$BASE_BRANCH" \
       > "$STATE_DIR/base-merge.log" 2>&1; then
    MAIN_NOTE="- 基线 ${BASE_BRANCH} 有 ${incoming} 个本分支没有的提交，已合并进来一起审查"
  else
    git -C "$REPO_ROOT" merge --abort >/dev/null 2>&1
    MAIN_NOTE="- **基线 ${BASE_BRANCH} 有 ${incoming} 个新提交，但与本分支冲突，未自动合并，需要人工处理（详见 base-merge.log）**"
  fi
  return 0
}

# 远端出现新分支（新项目可能另开分支上传）时在日志里点名，不自动并入。
report_new_branches() {
  local entry="$1"
  local known="$STATE_DIR/known-branches" current="$STATE_DIR/branches.now"
  git -C "$REPO_ROOT" ls-remote --heads origin 2>/dev/null \
    | awk '{print $2}' | sed 's|refs/heads/||' | sort > "$current" || return 0
  [[ -s "$current" ]] || { rm -f "$current"; return 0; }

  if [[ -f "$known" ]]; then
    local fresh b n
    fresh="$(comm -13 "$known" "$current")"
    for b in $fresh; do
      [[ "$b" == review-backup-* ]] && continue
      if git -C "$REPO_ROOT" fetch origin "$b" >/dev/null 2>&1; then
        n="$(git -C "$REPO_ROOT" rev-list --count HEAD..FETCH_HEAD 2>/dev/null || echo '?')"
      else
        n="?"
      fi
      echo "- **检测到远端新分支 ${b}（相对本分支新增 ${n} 个提交）。未自动并入；要纳入审查请把它合并到本分支，或告知如何处理**" >> "$entry"
    done
  fi
  mv "$current" "$known"
  return 0
}

# 本轮新拉取的改动都动了哪些顶层目录；没有对应审查步骤的目录要点名
CHECKED_DIRS="server art-lnb-master rider-android client-wechat deploy"
KNOWN_NO_CHECK="docs scripts .github packages design design-assets third_party tmp deliverables .claude"

project_hint() {
  local d="$1"
  if [[ -f "$REPO_ROOT/$d/package.json" ]]; then echo "，看结构是 Node 项目";
  elif [[ -f "$REPO_ROOT/$d/pom.xml" ]]; then echo "，看结构是 Maven 项目";
  elif [[ -f "$REPO_ROOT/$d/build.gradle" || -f "$REPO_ROOT/$d/build.gradle.kts" \
          || -f "$REPO_ROOT/$d/settings.gradle.kts" ]]; then echo "，看结构是 Gradle 项目";
  fi
}

describe_incoming() {
  local old="$1" entry="$2"
  [[ "$old" == "$(git -C "$REPO_ROOT" rev-parse HEAD)" ]] && return 0
  local dirs
  dirs=$(git -C "$REPO_ROOT" diff --name-only "$old"..HEAD 2>/dev/null \
    | awk -F/ 'NF>1{print $1} NF==1{print "(根目录)"}' | sort | uniq -c | sort -rn)
  [[ -z "$dirs" ]] && return 0
  echo "- 本轮新拉取的改动：$(echo "$dirs" | awk '{printf "%s(%s) ", $2, $1}')" >> "$entry"
  local d
  while read -r _ d; do
    [[ "$d" == "(根目录)" ]] && continue
    if [[ " $CHECKED_DIRS $KNOWN_NO_CHECK " != *" $d "* ]]; then
      echo "- **注意：目录 ${d} 没有对应的审查步骤$(project_hint "$d")，若是新项目请补充构建与检查方式**" >> "$entry"
    fi
    if [[ "$d" == "print-agent" ]]; then
      echo "- 注意：print-agent 有改动，但它只能在 Windows 上编译，本机跳过（CI 的 Print agent build 任务覆盖）" >> "$entry"
    fi
  done <<< "$dirs"
}

git_push_with_retry() {
  local attempt
  for attempt in 1 2 3 4; do
    git -C "$REPO_ROOT" push origin "$BRANCH" && return 0
    # 推送被拒说明远端又前进了：重新同步（含冲突处理）再试
    sync_with_remote
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

  # 0. 上一轮若在提交前崩掉会留下未提交改动，先收拢，绝不带着脏树做合并
  if [[ -n "$(git -C "$REPO_ROOT" status --porcelain)" ]]; then
    git -C "$REPO_ROOT" add -A
    git -C "$REPO_ROOT" commit -q -m "chore(review): 收拢上一轮遗留的工作区改动" || true
    echo "- 发现上一轮遗留的未提交改动，已先行收拢为独立提交" >> "$entry"
  fi

  # 1. 同步：本分支 -> 基线 main -> 新分支检测（分叉、冲突都在这里消化）
  sync_with_remote
  echo "- ${SYNC_NOTE}" >> "$entry"
  sync_with_base
  [[ -n "$MAIN_NOTE" ]] && echo "$MAIN_NOTE" >> "$entry"
  report_new_branches "$entry"
  describe_incoming "$SYNC_OLD_HEAD" "$entry"

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

  # 5. 骑手端单测 + lint
  run_step rider "$STATE_DIR/rider.log" 1800 \
    bash -c "cd '$REPO_ROOT/rider-android' && ./gradlew --console=plain testDebugUnitTest lintDebug"
  rc=$?
  echo "- 骑手端 testDebugUnitTest + lintDebug：$(summarize $rc "$STATE_DIR/rider.log")" >> "$entry"
  [[ $rc -ne 0 ]] && failures=$((failures + 1))

  # 6. 小程序全量语法检查（与 CI 同一脚本）
  run_step miniprogram "$STATE_DIR/miniprogram.log" 300 \
    bash -c "cd '$REPO_ROOT' && node .github/scripts/check-miniprogram.mjs client-wechat"
  rc=$?
  echo "- 小程序语法检查：$(summarize $rc "$STATE_DIR/miniprogram.log")" >> "$entry"
  [[ $rc -ne 0 ]] && failures=$((failures + 1))

  # 7. 运维模板与发布契约（与 CI 的 operations 任务同口径）
  run_step operations "$STATE_DIR/operations.log" 300 \
    bash -c "cd '$REPO_ROOT' \
      && bash -n deploy/scripts/lib/common.sh deploy/scripts/validate-env.sh \
           deploy/scripts/readiness.sh deploy/scripts/build-release.sh \
           deploy/scripts/render-nginx.sh deploy/scripts/install-release.sh \
           deploy/scripts/go-live.sh deploy/scripts/rollback.sh \
           deploy/scripts/restore-drill.sh scripts/auto-review.sh \
      && node --check deploy/pm2/ecosystem.config.cjs \
      && python3 .github/scripts/check_release_contract.py"
  rc=$?
  echo "- 运维模板与发布契约：$(summarize $rc "$STATE_DIR/operations.log")" >> "$entry"
  [[ $rc -ne 0 ]] && failures=$((failures + 1))

  # 8. 密钥扫描
  run_step secrets "$STATE_DIR/secrets.log" 300 \
    bash -c "cd '$REPO_ROOT' && python3 .github/scripts/audit_tracked_files.py"
  rc=$?
  echo "- 密钥与归档扫描：$(summarize $rc "$STATE_DIR/secrets.log")" >> "$entry"
  [[ $rc -ne 0 ]] && failures=$((failures + 1))

  # 9. 结论 + 提交推送
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
      echo "由 scripts/auto-review.sh 生成。每一轮：同步远端与基线 -> 三端与小程序、运维模板全量检查 ->"
      echo "eslint 自动修复 -> 记录本文件 -> 提交推送。最新一轮在最上面。"
      echo
    } > "$LOG_FILE"
  fi
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
echo "[$(ts)] 自动审查循环启动，分支 ${BRANCH}（基线 ${BASE_BRANCH}），起始轮次 ${round}，轮间隔 ${SLEEP_SECONDS}s，停止方式：touch $STOP_FILE"

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
