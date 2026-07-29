#!/usr/bin/env bash
#
# arch-review-pass — 記錄「實作程式碼已通過 arch-auditor 架構符規查證」
#
# 在 arch-auditor 回報、critical／major 全數修完後呼叫。
# 寫入的是**當下實作程式碼的內容雜湊**；之後 code 再被改動，雜湊就對不上，
# notify-code-changed 會在下次寫入時重新提示。避免「查過一次就永久放行」。
#
# 與 spec-review-pass.sh 是平行的兩套狀態，互不干擾：
#   .claude/.spec-review-state   ← spec/ 的雜湊（spec-auditor）
#   .claude/.arch-review-state   ← 實作碼的雜湊（arch-auditor）← 本檔管的
#
# 用法：
#   bash .claude/hooks/arch-review-pass.sh              # 記錄查證完成
#   bash .claude/hooks/arch-review-pass.sh --status     # 只看目前狀態
#   bash .claude/hooks/arch-review-pass.sh --clear      # 清除紀錄（強制下次重查）

set -uo pipefail

ROOT=$(git rev-parse --show-toplevel 2>/dev/null) || { echo "✗ 不在 git repo 內" >&2; exit 1; }
cd "$ROOT" || exit 1

MARKER=".claude/.arch-review-state"
# notify-code-changed.sh 的「本週期已提示過」旗標。記錄 / 清除時都要一併重設，
# 否則下一輪程式碼變更不會再提示（靜默失效，比沒裝還糟）。
NOTIFIED=".claude/.arch-review-notified"

# 必須與 notify-code-changed.sh 的 impl_hash 逐字一致，否則兩邊算出不同雜湊，
# 記錄了也對不上 → 每次寫入都被提示，最後只會被關掉。
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

case "${1:-}" in
  --clear)
    rm -f "$MARKER" "$NOTIFIED"
    echo "✓ 已清除架構查證紀錄，下次寫入實作程式碼時會重新提示"
    exit 0
    ;;
  --status)
    current=$(impl_hash)
    recorded=$(awk 'NR==1{print $1}' "$MARKER" 2>/dev/null || true)
    if [ -z "$recorded" ]; then
      echo "狀態：無查證紀錄（實作碼現況 ${current:0:12}）"
    elif [ "$recorded" = "$current" ]; then
      echo "狀態：已通過架構查證且實作碼未再變動（${current:0:12}）"
    else
      echo "狀態：查證後實作碼又被改過，需重查（紀錄 ${recorded:0:12} ≠ 現況 ${current:0:12}）"
    fi
    exit 0
    ;;
esac

mkdir -p "$(dirname "$MARKER")"
h=$(impl_hash)
printf '%s\n' "$h" > "$MARKER"
rm -f "$NOTIFIED"      # 下一輪程式碼變更要能重新提示
{
  printf '# arch-review passed\n'
  printf '# branch: %s\n' "$(git rev-parse --abbrev-ref HEAD 2>/dev/null)"
  printf '# 此檔為本機狀態，不進版控。實作程式碼再變動即失效。\n'
} >> "$MARKER"

echo "✓ 已記錄架構查證通過（${h:0:12}）；實作程式碼若再變動會重新提示"
