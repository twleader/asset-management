#!/usr/bin/env bash
#
# codex-review.sh — 以本機 Codex CLI 執行 spec／架構審查（取代 Claude 的
# spec-auditor／arch-auditor subagent，以節省 Claude 用量）。
#
# 判準仍住在 .claude/agents/{spec,arch}-auditor.md（唯一來源，不在此複製），
# 本腳本只負責：組 prompt（判準＋本輪材料＋前一輪處理紀錄）→ 唯讀跑 Codex → 檢查輸出格式。
# Codex 只讀、只回報；每輪修正一律由 Claude 執行，再進下一輪複審。最多審三輪，
# 第 3 輪的問題同樣由 Claude 修完後結束，不送第 4 輪（本腳本拒絕輪次 >3）。
#
# 用法：
#   bash scripts/codex-review.sh <spec|arch> <材料檔> <輪次 1-3> <輸出目錄> [前一輪處理紀錄檔]
#
#   材料檔：spec-check.sh 完整輸出／完整 diff／未追蹤新檔內容／本次目標（由呼叫端組好）
#   輸出：<輸出目錄>/codex-<kind>-review-round<N>.md（第一行為 VERDICT）
#   輸出目錄請用 session scratchpad，不要放進 repo。
#
# 模型：預設 gpt-6.1-sol、reasoning effort high（使用者指定）；
#       可用 CODEX_REVIEW_MODEL／CODEX_REVIEW_EFFORT 覆寫。
# 失敗（找不到執行檔、登入失效、非零退出、輸出格式不符）一律非零退出，
# 呼叫端不得改用其他審查者冒充 Codex。

set -euo pipefail

usage() { sed -n '11,18p' "$0" >&2; exit 64; }
[ $# -ge 4 ] || usage

KIND=$1; MATERIALS=$2; ROUND=$3; OUTDIR=$4; PREV=${5:-}

case "${KIND}" in spec|arch) ;; *) echo "kind 必須是 spec 或 arch" >&2; exit 64 ;; esac
case "${ROUND}" in 1|2|3) ;; *) echo "輪次只能是 1–3（最多審三輪）" >&2; exit 64 ;; esac
[ -s "${MATERIALS}" ] || { echo "材料檔不存在或為空：${MATERIALS}" >&2; exit 64; }
if [ "${ROUND}" != 1 ]; then
  [ -n "${PREV}" ] && [ -s "${PREV}" ] || { echo "第 ${ROUND} 輪必須提供前一輪處理紀錄檔" >&2; exit 64; }
fi

ROOT=$(git rev-parse --show-toplevel)
CRITERIA="${ROOT}/.claude/agents/${KIND}-auditor.md"
[ -f "${CRITERIA}" ] || { echo "找不到判準檔：${CRITERIA}" >&2; exit 1; }

MODEL=${CODEX_REVIEW_MODEL:-gpt-6.1-sol}
EFFORT=${CODEX_REVIEW_EFFORT:-high}

CODEX=$(command -v codex 2>/dev/null || true)
if [ -z "${CODEX}" ]; then
  CODEX=/Applications/ChatGPT.app/Contents/Resources/codex-cli/CodexCLI.app/Contents/MacOS/codex
fi
[ -x "${CODEX}" ] || { echo "找不到 Codex CLI（PATH 與 ChatGPT.app 內建路徑皆無）" >&2; exit 1; }

mkdir -p "${OUTDIR}"
PROMPT="${OUTDIR}/codex-${KIND}-prompt-round${ROUND}.md"
OUT="${OUTDIR}/codex-${KIND}-review-round${ROUND}.md"

{
  cat <<EOF
# 角色

你是 asset-management 專案的獨立審查者（第 ${ROUND}/3 輪）。作者是 Claude，不是你。
你**只讀、只回報**：禁止修改任何檔案，禁止執行任何會寫入檔案、git commit／push、
呼叫 POST API、寄信、重建容器或對券商下單的命令。可用 git diff／grep -ran／rg／ls／cat 查證。
repo 路徑：${ROOT}

# 審查判準（以下全文即你的判準；其中提到的「Agent tool／subagent／主 agent」
# 一律理解為：你是審查者、Claude 是修正者）

EOF
  # 去掉 YAML frontmatter
  awk 'BEGIN{fm=0} NR==1&&/^---$/{fm=1;next} fm&&/^---$/{fm=0;next} !fm' "${CRITERIA}"
  echo
  echo "# 本輪材料"
  echo
  cat "${MATERIALS}"
  if [ -n "${PREV}" ]; then
    echo
    echo "# 前一輪問題與 Claude 的處理"
    echo
    echo "逐項確認是否真的解決（自行 grep／讀檔查證，不採信說法）；判定為誤判者評估理由是否成立；並找新問題。"
    echo
    cat "${PREV}"
  fi
  cat <<'EOF'

# 輸出格式（嚴格遵守）

- 第一行只能是 `VERDICT: PASS` 或 `VERDICT: CHANGES_REQUESTED`，前面不得有任何文字。
- 只有存在 Critical 或 Major finding 時才可給 `CHANGES_REQUESTED`；只剩 Minor 給 `PASS`。
- 之後依判準中「報告格式」段落輸出，以下為機器檢查項，缺一即視為無效報告：
  - 二級標題（`## `）只能是 `## 判定`、`## Findings`、`## 已查無發現`、`## 自我挑戰`（各恰好一次）
    與可選的 `## 無法查證`；不得使用其他二級標題。
  - severity 標題只能出現在 `## Findings` 段內；所有標題一律從行首寫起，不得有前置空白。
  - `## 判定` 段內必須恰好一行、整行為 `critical: N   major: N   minor: N`，且數字等於 finding 標題數。
  - 每條 finding 標題為 `### [Critical] …`／`### [Major] …`／`### [Minor] …`，內容含檔案:行號、
    實際查證證據、被違反的出處、建議修法。
- 用台灣繁體中文。
EOF
} > "${PROMPT}"

echo "Codex 審查：kind=${KIND} round=${ROUND} model=${MODEL} effort=${EFFORT}" >&2
"${CODEX}" exec -m "${MODEL}" -c model_reasoning_effort="${EFFORT}" \
  -s read-only -C "${ROOT}" --skip-git-repo-check \
  -o "${OUT}" - < "${PROMPT}"

[ -s "${OUT}" ] || { echo "Codex 沒有產出報告：${OUT}" >&2; exit 1; }
first=$(head -n 1 "${OUT}" | tr -d '\r')
case "${first}" in
  "VERDICT: PASS"|"VERDICT: CHANGES_REQUESTED") ;;
  *) echo "Codex 報告第一行格式不符（${first}）：${OUT}" >&2; exit 1 ;;
esac

# ── 報告結構與一致性檢查（缺段落、計數與 finding 不符、VERDICT 與 severity 矛盾 → 非零退出）
fail() { echo "Codex 報告不合格：$1（${OUT}）" >&2; exit 1; }
# 不支援縮排標題（前置空白的 #），一律拒絕，避免繞過下方以行首為準的段落切分與 severity 計數
indented=$(grep -En '^[[:space:]]+#' "${OUT}" | head -n 1 || true)
[ -z "${indented}" ] || fail "不支援有前置空白的標題（第 ${indented%%:*} 行）"
# 二級標題白名單：必要四段各恰好一次；`## 無法查證`（arch 判準）可選、至多一次；
# 其他任何 `## ` 標題一律拒絕，避免把 finding 藏在非預期段落而漏算。
struct_err=$(awk '
  /^## / {
    h=$0; sub(/[[:space:]]+$/, "", h)
    if (h=="## 判定"||h=="## Findings"||h=="## 已查無發現"||h=="## 自我挑戰"||h=="## 無法查證") { seen[h]++; sec=h }
    else { print "非預期的二級標題：" $0; aborted=1; exit }
    next
  }
  END {
    if (aborted) exit
    split("## 判定|## Findings|## 已查無發現|## 自我挑戰", req, "|")
    for (i in req) if (seen[req[i]]!=1) { print "段落「" req[i] "」須恰好出現一次（實際 " seen[req[i]]+0 " 次）"; exit }
    if (seen["## 無法查證"]>1) print "段落「## 無法查證」至多一次"
  }' "${OUT}")
[ -z "${struct_err}" ] || fail "${struct_err}"
section() { awk -v want="$1" '/^## /{h=$0; sub(/[[:space:]]+$/, "", h); f=(h==want); next} f' "${OUT}"; }
findings=$(section '## Findings')
# Findings 段內每個 ### 標題都必須是三種 severity 之一（如 [HIGH] 會被拒絕）
bad=$(printf '%s\n' "${findings}" | grep -E '^###' \
      | grep -Ev '^###[[:space:]]+\[(Critical|Major|Minor)\][[:space:]]+[^[:space:]]' || true)
[ -z "${bad}" ] || fail "Findings 內有不合格式的標題：$(printf '%s' "${bad}" | head -n 1)"
# 任何 Findings 段以外出現的 severity 標題都視為位置錯誤
outside=$(awk '/^## /{h=$0; sub(/[[:space:]]+$/, "", h); f=(h=="## Findings"); next} !f && /^#+[[:space:]]*\[(Critical|Major|Minor|CRITICAL|MAJOR|MINOR|critical|major|minor)\]/' "${OUT}")
[ -z "${outside}" ] || fail "Findings 段以外出現 severity 標題：$(printf '%s' "${outside}" | head -n 1)"
# 計數行：只在「判定」段內找，且必須恰好一行、整行符合格式
count_re='^critical:[[:space:]]*[0-9]+[[:space:]]+major:[[:space:]]*[0-9]+[[:space:]]+minor:[[:space:]]*[0-9]+[[:space:]]*$'
counts=$(section '## 判定' | grep -E "${count_re}" || true)
n_lines=$(printf '%s' "${counts}" | grep -c . || true)
[ "${n_lines}" = 1 ] || fail "「## 判定」段內須恰好一行「critical: N   major: N   minor: N」（實際 ${n_lines} 行）"
read -r n_crit n_major n_minor <<< "$(printf '%s\n' "${counts}" | grep -Eo '[0-9]+' | tr '\n' ' ')"
h_crit=$(printf '%s\n' "${findings}" | grep -Ec '^###[[:space:]]+\[Critical\]' || true)
h_major=$(printf '%s\n' "${findings}" | grep -Ec '^###[[:space:]]+\[Major\]' || true)
h_minor=$(printf '%s\n' "${findings}" | grep -Ec '^###[[:space:]]+\[Minor\]' || true)
[ "${n_crit}" = "${h_crit}" ] && [ "${n_major}" = "${h_major}" ] && [ "${n_minor}" = "${h_minor}" ] \
  || fail "計數（critical ${n_crit}／major ${n_major}／minor ${n_minor}）與 finding 標題數（${h_crit}／${h_major}／${h_minor}）不符"
if [ $((n_crit + n_major)) -gt 0 ]; then expected="VERDICT: CHANGES_REQUESTED"; else expected="VERDICT: PASS"; fi
[ "${first}" = "${expected}" ] || fail "VERDICT 與 severity 矛盾（應為 ${expected}）"

echo "${first}（critical ${n_crit}／major ${n_major}／minor ${n_minor}）"
echo "報告：${OUT}"
