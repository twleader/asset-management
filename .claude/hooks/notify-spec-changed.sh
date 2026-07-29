#!/usr/bin/env bash
#
# notify-spec-changed — PostToolUse 提示：spec 剛被改過，該跑 /spec-review 了
#
# 這是 SDD 第 4 步的「主動端」。與 require-spec-review.sh 的分工：
#
#   notify-spec-changed   （PostToolUse，寫入 spec/ 後）  → 立刻提醒該審查了
#   require-spec-review   （PreToolUse，寫入實作檔前）    → 沒審過就硬擋
#
# 為什麼需要主動端：只有硬擋的話，模型是「撞到牆才回頭審」——中間可能已經想了
# 一整套實作方向。在 spec 落地的當下就提醒，順序才是對的（審完再想怎麼寫）。
#
# 為什麼 hook 不直接叫 spec-auditor：hook 是 shell 指令，叫不到 Agent tool。
# 唯一的替代是在這裡跑 headless `claude -p`，但單次審查約 6～7 分鐘，
# 每次寫 spec 都同步卡住不可接受，且巢狀 session 難以除錯。故改為提示模型自己跑。
#
# 每個「spec 變髒」的週期只提示一次（避免連改五個 spec 檔就被唸五次）；
# 由 spec-review-pass.sh 記錄通過時清除，下一輪再變髒才會重新提示。

set -uo pipefail

[ "${SKIP_SPEC_GATE:-0}" = "1" ] && exit 0

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
  /*)        exit 0 ;;
  *)         rel="$target" ;;
esac
cd "$ROOT" || exit 0

# 只在寫入 spec/ 時作用
case "$rel" in
  spec/*) ;;
  *) exit 0 ;;
esac

MARKER=".claude/.spec-review-state"
NOTIFIED=".claude/.spec-review-notified"

spec_hash() {
  find spec -type f -not -path '*/.*' -print0 2>/dev/null \
    | LC_ALL=C sort -z \
    | xargs -0 shasum 2>/dev/null \
    | shasum | awk '{print $1}'
}

current=$(spec_hash)
recorded=$(awk 'NR==1{print $1}' "$MARKER" 2>/dev/null || true)

# 已審過且 spec 未再變動 → 沒事
[ -n "$recorded" ] && [ "$recorded" = "$current" ] && exit 0

# 本週期已經提示過 → 不重複唸
[ -f "$NOTIFIED" ] && exit 0

mkdir -p "$(dirname "$NOTIFIED")"
printf '%s\n' "$current" > "$NOTIFIED"

cat >&2 <<'EOF'
◆ SDD 第 4 步：spec 已變更，請先跑對抗式審查再進入實作

    /spec-review

流程：scripts/spec-check.sh 取機械證據 → 派唯讀 spec-auditor 查證
      → 修完 critical／major → bash .claude/hooks/spec-review-pass.sh 記錄通過

在記錄通過之前，對 backend/** ／ bff/** ／ external-materials-service/** ／
frontend/src/views/*.vue ／ frontend/src/router/** ／ db/changelog/** 的寫入
都會被 PreToolUse 閘門擋下。

（本提示每個 spec 變更週期只出現一次。繼續編輯 spec/ 不會再被打斷。）
EOF

exit 2
