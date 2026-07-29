#!/usr/bin/env bash
#
# spec-review-pass — 記錄「spec/ 已通過對抗式審查」
#
# 由 /spec-review skill 的 Step 3 在 critical／major 全數修完後呼叫。
# 寫入的是**當下 spec/ 的內容雜湊**；之後 spec 再被改動，雜湊就對不上，
# require-spec-review 會要求重審。這是為了避免「審過一次就永久放行」。
#
# 用法：
#   bash .claude/hooks/spec-review-pass.sh              # 記錄通過
#   bash .claude/hooks/spec-review-pass.sh --status     # 只看目前狀態
#   bash .claude/hooks/spec-review-pass.sh --clear      # 清除紀錄（強制下次重審）

set -uo pipefail

ROOT=$(git rev-parse --show-toplevel 2>/dev/null) || { echo "✗ 不在 git repo 內" >&2; exit 1; }
cd "$ROOT" || exit 1

MARKER=".claude/.spec-review-state"
# notify-spec-changed.sh 的「本週期已提示過」旗標。記錄通過 / 清除時都要一併重設，
# 否則下一輪 spec 變更不會再提示（靜默失效，比沒裝還糟）。
NOTIFIED=".claude/.spec-review-notified"

spec_hash() {
  find spec -type f -not -path '*/.*' -print0 2>/dev/null \
    | LC_ALL=C sort -z \
    | xargs -0 shasum 2>/dev/null \
    | shasum | awk '{print $1}'
}

case "${1:-}" in
  --clear)
    rm -f "$MARKER" "$NOTIFIED"
    echo "✓ 已清除審查紀錄，下次寫入實作程式碼前需重跑 /spec-review"
    exit 0
    ;;
  --status)
    current=$(spec_hash)
    recorded=$(awk 'NR==1{print $1}' "$MARKER" 2>/dev/null || true)
    if [ -z "$recorded" ]; then
      echo "狀態：無審查紀錄（spec/ 現況 ${current:0:12}）"
    elif [ "$recorded" = "$current" ]; then
      echo "狀態：已通過審查且 spec/ 未再變動（${current:0:12}）"
    else
      echo "狀態：審查後 spec/ 又被改過，需重審（紀錄 ${recorded:0:12} ≠ 現況 ${current:0:12}）"
    fi
    exit 0
    ;;
esac

mkdir -p "$(dirname "$MARKER")"
h=$(spec_hash)
printf '%s\n' "$h" > "$MARKER"
rm -f "$NOTIFIED"      # 下一輪 spec 變更要能重新提示
{
  printf '# spec-review passed\n'
  printf '# branch: %s\n' "$(git rev-parse --abbrev-ref HEAD 2>/dev/null)"
  printf '# 此檔為本機狀態，不進版控。spec/ 再變動即失效。\n'
} >> "$MARKER"

echo "✓ 已記錄 spec 審查通過（${h:0:12}）；spec/ 若再變動需重審"
