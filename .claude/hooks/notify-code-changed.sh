#!/usr/bin/env bash
#
# notify-code-changed — PostToolUse 提示：實作程式碼剛被改過，該跑 arch-auditor 了
#
# 這是 SDD 流程「實作之後」的查證提示，與 spec 那一組的分工：
#
#   notify-spec-changed   （PostToolUse，寫入 spec/ 後）    → 提醒審**規格**（spec-auditor）
#   require-spec-review   （PreToolUse，寫入實作檔前）      → 沒審規格就硬擋
#   notify-code-changed   （PostToolUse，寫入實作檔後）     → 提醒審**程式碼**（arch-auditor）← 本檔
#   commit-msg            （commit 時）                     → 改了 code 必須有 spec
#
# 為什麼只提示、不硬擋：程式寫完的下一步就是 commit，要硬擋只能攔 `git commit`，
# 那會干擾到別的 session 的提交流程（本專案同時有 30+ 個 worktree 在跑）。
# 經評估後刻意選擇「提示但不阻斷」——這一關的價值在於「寫完當下就想起要查」，
# 而不是在 commit 當下才被攔。
#
# 為什麼 hook 不直接叫 arch-auditor：hook 是 shell 指令，叫不到 Agent tool。
# 唯一的替代是在這裡跑 headless `claude -p`，但那會讓每次存檔都同步卡住數分鐘，
# 不可接受。故改為提示模型自己派 subagent。（與 notify-spec-changed 同樣的取捨。）
#
# 每個「實作碼變髒」的週期只提示一次（避免連改十個檔就被唸十次）；
# 由 arch-review-pass.sh 記錄查證完成時清除，下一輪再變髒才會重新提示。
#
# 逃生門：SKIP_ARCH_GATE=1

set -uo pipefail

[ "${SKIP_ARCH_GATE:-0}" = "1" ] && exit 0

payload=$(cat)

extract_path() {
  if command -v jq >/dev/null 2>&1; then
    printf '%s' "$payload" | jq -r '.tool_input.file_path // .tool_input.notebook_path // empty' 2>/dev/null
  else
    printf '%s' "$payload" | python3 -c 'import json,sys
try:
    d = json.load(sys.stdin).get("tool_input", {})
    print(d.get("file_path") or d.get("notebook_path") or "")
except Exception:
    print("")' 2>/dev/null
  fi
}

target=$(extract_path)
[ -z "$target" ] && exit 0

ROOT=$(git rev-parse --show-toplevel 2>/dev/null) || exit 0
case "$target" in
  "$ROOT"/*) rel="${target#"$ROOT"/}" ;;
  /*)        exit 0 ;;   # repo 外的檔案，不管
  *)         rel="$target" ;;
esac
cd "$ROOT" || exit 0

# ── 這個檔案算不算「實作程式碼」 ──────────────────────────────────
# 與 require-spec-review.sh 的 is_impl 保持同一份清單。兩邊漂掉會很難察覺：
# 一邊擋、一邊不提示，看起來像 hook 壞了。改這裡記得同步改那邊。
is_impl() {
  printf '%s' "$1" | grep -qE \
    -e '^backend/src/main/java/.*\.java$' \
    -e '^bff/src/main/java/.*\.java$' \
    -e '^external-materials-service/src/main/java/.*\.java$' \
    -e '^backend/src/main/resources/db/changelog/' \
    -e '^frontend/src/views/.*\.vue$' \
    -e '^frontend/src/router/'
}
is_impl "$rel" || exit 0

MARKER=".claude/.arch-review-state"
NOTIFIED=".claude/.arch-review-notified"

# 涵蓋範圍必須與 is_impl 一致，否則「改了會被提示、但改動不影響雜湊」→ 記錄一次就永久放行。
impl_hash() {
  {
    find backend/src/main/java bff/src/main/java external-materials-service/src/main/java \
         -type f -name '*.java' 2>/dev/null
    find backend/src/main/resources/db/changelog frontend/src/router -type f 2>/dev/null
    find frontend/src/views -type f -name '*.vue' 2>/dev/null
  } | LC_ALL=C sort | tr '\n' '\0' \
    | xargs -0 shasum 2>/dev/null \
    | shasum | awk '{print $1}'
}

current=$(impl_hash)
recorded=$(awk 'NR==1{print $1}' "$MARKER" 2>/dev/null || true)

# 已查證過且實作碼未再變動 → 沒事
[ -n "$recorded" ] && [ "$recorded" = "$current" ] && exit 0

# 本週期已經提示過 → 不重複唸
[ -f "$NOTIFIED" ] && exit 0

mkdir -p "$(dirname "$NOTIFIED")"
printf '%s\n' "$current" > "$NOTIFIED"

cat >&2 <<'EOF'
◆ 實作程式碼已變更，commit 前請跑架構符規查證

    用 Agent tool 派 subagent_type: arch-auditor（唯讀，不會改你的檔案）

它是 diff-scoped 的——**必須**把本次變更的完整 diff 餵給它，否則它會退化成
全樹掃描，把既有技術債整包倒出來（main 上光 controller 直接注入 Repository
就有 2 支），真正的新增違規會被淹掉。先蒐集材料：

    git diff origin/main...HEAD --stat
    git diff origin/main...HEAD -- backend/ bff/ external-materials-service/ frontend/
    git diff -- .                     # 未 commit 的
    git ls-files --others --exclude-standard    # 未追蹤新檔（diff 看不到）

一併告訴它「這次想達成什麼」，讓它能區分「違反架構」與「這版還沒寫完」。

查證完、critical／major 修完後記錄：

    bash .claude/hooks/arch-review-pass.sh

（本提示每個實作變更週期只出現一次，繼續寫 code 不會再被打斷。
  這一關只提示、不會擋 commit。逃生門：SKIP_ARCH_GATE=1）
EOF

exit 2
