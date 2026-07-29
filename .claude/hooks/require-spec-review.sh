#!/usr/bin/env bash
#
# require-spec-review — PreToolUse 閘門：spec 改了就必須審過，才准動實作程式碼
#
# 這支補的洞：SDD 第 4 步（/spec-review）在此之前**沒有任何機器在保證會被跑到**。
#   - CLAUDE.md 的「不得跳過」是文字規範，靠模型自律。
#   - skill 的觸發詞是模型判斷，機率性的。
#   - commit-msg hook 是唯一的真閘門，但它在 **commit 當下**才跑（程式早就寫完了），
#     而且只驗「spec/ 有沒有被碰」，不驗有沒有審過。
#
# 與 commit-msg hook 的分工（刻意不重疊）：
#   commit-msg           改了 code → 必須有 spec        （同步閘門，commit 時）
#   require-spec-review  改了 spec → 必須審過才能寫 code（審查閘門，寫入時）
#
# 因此「完全沒動 spec/」的純 bug fix / 樣式調整**不會**被這支擋——那種情境本來就
# 由 commit-msg 負責。這是為了讓閘門只在真正的 SDD 週期裡收緊，避免變成人人繞道。
#
# 逃生門：SKIP_SPEC_GATE=1
#
# 安裝：由 .claude/settings.json 的 hooks.PreToolUse 掛載，無須手動設定。

set -uo pipefail

# ── 逃生門 ────────────────────────────────────────────────────────
[ "${SKIP_SPEC_GATE:-0}" = "1" ] && exit 0

payload=$(cat)

# ── 取出被寫入的檔案路徑 ──────────────────────────────────────────
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

# ── 轉成 repo 相對路徑 ────────────────────────────────────────────
# hook 的 cwd 不保證等於 repo root，且 file_path 可能是絕對路徑。
ROOT=$(git rev-parse --show-toplevel 2>/dev/null) || exit 0
case "$target" in
  "$ROOT"/*) rel="${target#"$ROOT"/}" ;;
  /*)        exit 0 ;;   # repo 外的檔案，不管
  *)         rel="$target" ;;
esac

cd "$ROOT" || exit 0

# ── 這個檔案算不算「實作程式碼」 ──────────────────────────────────
# 比 commit-msg 的清單更寬：那份漏了 service/、repository/、external-materials-service/**，
# Task 195 兩處排程漂移就是從那些漏洞溜過去的（CLAUDE.md 自己有記這件事）。
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

# ── 現在是不是「有 spec 變更」的 SDD 週期 ─────────────────────────
BASE="${SPEC_GATE_BASE:-origin/main}"
if ! git rev-parse --verify "$BASE" >/dev/null 2>&1; then
  # base ref 不在（未 fetch / 新 clone）→ 放行，但明講一聲。
  # 這裡刻意 fail-open：一個壞掉的閘門把所有寫入都擋死，比漏擋更糟。
  echo "⚠ require-spec-review：找不到 base ref $BASE，本次不設閘門（請先 git fetch origin）" >&2
  exit 0
fi

spec_touched() {
  git diff --quiet "$BASE"...HEAD -- spec/ 2>/dev/null || return 0
  git diff --quiet -- spec/ 2>/dev/null || return 0
  git diff --cached --quiet -- spec/ 2>/dev/null || return 0
  [ -n "$(git ls-files --others --exclude-standard -- spec/ 2>/dev/null)" ] && return 0
  return 1
}
spec_touched || exit 0     # 沒動 spec/ → 不是 SDD 週期，交給 commit-msg 管

# ── 審查證據比對 ──────────────────────────────────────────────────
# marker 記錄「審查通過當下 spec/ 的內容雜湊」。spec 後來又改了就必須重審——
# 否則「審過一次就永久放行」等於沒擋。
MARKER=".claude/.spec-review-state"

spec_hash() {
  find spec -type f -not -path '*/.*' -print0 2>/dev/null \
    | LC_ALL=C sort -z \
    | xargs -0 shasum 2>/dev/null \
    | shasum | awk '{print $1}'
}

current=$(spec_hash)
recorded=$(awk 'NR==1{print $1}' "$MARKER" 2>/dev/null || true)

[ -n "$recorded" ] && [ "$recorded" = "$current" ] && exit 0

# ── 阻擋並說明 ────────────────────────────────────────────────────
if [ -z "$recorded" ]; then
  why="本輪 spec/ 有變更，但找不到審查紀錄（$MARKER 不存在）。"
else
  why="審查通過後 spec/ 又被修改過（紀錄 ${recorded:0:12} ≠ 現況 ${current:0:12}），需重審。"
fi

cat >&2 <<EOF
✗ SDD 閘門：spec 尚未通過對抗式審查，不得開始實作

    想寫入：$rel
    原因　：$why

依 CLAUDE.md，spec 改完必須先跑第 4 步再進實作：

    /spec-review

（會先跑 scripts/spec-check.sh 取機械證據，再派唯讀的 spec-auditor 查證。
  critical 與 major 修完即可開工；審查通過後由該 skill 寫入審查紀錄。）

如何處理：
    A. 跑 /spec-review，修完 critical／major → 本閘門自動放行
    B. 這次確實不需要審查（例如只是回填任務完成報告）：
       SKIP_SPEC_GATE=1 開頭執行，或請使用者確認後改用該環境變數
    C. 純樣式 / typo：那類變更通常不會動到 spec/，本閘門本來就不會擋——
       若你動了 spec/ 又說不需審查，請先想清楚是不是真的不用

注意：本閘門只管「spec 改了有沒有審」。「改了 code 有沒有 spec」由 commit-msg hook 管。
EOF

exit 2
