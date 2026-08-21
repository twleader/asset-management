#!/usr/bin/env bash
#
# spec-check — spec 變更的機械前置檢查
#
# 用途：在對抗式 spec 審查（.claude/skills/spec-review）之前先跑，把「不需要判斷力、
#       純比對就能證明」的缺陷直接列出來，讓 LLM 審查專心處理需要判斷的部分。
#
# 這些檢查全部來自本專案實際犯過的錯（見 spec/tasks.md Task 148 / 175 / 183 / 195 / 197 / 201）：
#   B1 Task / Requirement 編號碰撞      ← 一年內 12 筆 commit 專門在避讓
#   B2 Liquibase changeset 版號碰撞     ← 同上，且改名會觸發重跑炸站（Task 207）
#   B3 changeset 非冪等                 ← 745de990 整站 crash loop 的直接成因
#   B4 changeset 未註冊 master.yaml
#   B5 spec 宣稱的測試類不存在          ← Task 160「單元驗證」但無該測試檔
#   B6 @Scheduled 變更未同步排程登錄表  ← Task 195 兩處漂移的根因
#   B7 文件計數宣告漂移
#   B9 9090 gateway/OpenAPI 契約漂移 ← Task 347 將 runtime/Swagger 同步升為機械閘門
#
# 用法：
#   scripts/spec-check.sh [base-ref]      # base-ref 預設 origin/main
#
# 輸出：BLOCK = 已證實的缺陷（必須修）；CHECK = 需人／LLM 判定的線索。
# 離開碼：有任何 BLOCK → 1；否則 0。

set -uo pipefail

BASE="${1:-origin/main}"
ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT" || exit 2

git rev-parse --verify "$BASE" >/dev/null 2>&1 || {
  echo "✗ 找不到 base ref：$BASE"
  echo "  （若要比對未 fetch 的遠端，請先 git fetch origin）"
  exit 2
}

BLOCKS=0
CHECKS=0
block() { echo "BLOCK │ $*"; BLOCKS=$((BLOCKS + 1)); }
check() { echo "CHECK │ $*"; CHECKS=$((CHECKS + 1)); }

# 未追蹤（新增但尚未 git add）的檔案必須一起算。這是本檢查最容易出錯的地方：
# 新的自足任務檔與新的 Liquibase changeset「本質上都是新檔案」，執行這支腳本的時點
# （實作前審查）又必然早於 commit，若只看 git diff 就會對最該擋的情境靜默放行。
# spec/tasks/archive/ 是凍結歷史，掃它只會對既有內容產生假 BLOCK，明確排除。
untracked_files() {
  git ls-files --others --exclude-standard 2>/dev/null | grep -v '^spec/tasks/archive/' || true
}

DIFF_FILES=$({ git diff --name-only "$BASE"...HEAD; git diff --name-only; \
               git diff --cached --name-only; untracked_files; } 2>/dev/null \
             | sort -u | grep -v '^$' || true)

FILE_COUNT=$(printf '%s\n' "$DIFF_FILES" | grep -c . || true)
echo "════ spec-check ════"
echo "base: $BASE ($(git rev-parse --short "$BASE"))  head: $(git rev-parse --abbrev-ref HEAD)"
echo "變更檔數: ${FILE_COUNT:-0}（含未追蹤新檔）"
# origin/main 過期時撞號檢查會靜默失效——別人推進 main 後你的本地 ref 還停在舊點。
if [ "$BASE" = "origin/main" ]; then
  age=$(( ( $(date +%s) - $(git log -1 --format=%ct origin/main 2>/dev/null || date +%s) ) / 3600 ))
  [ "$age" -gt 24 ] && check "origin/main 上次更新在 ${age} 小時前，撞號檢查可能失準——建議先 git fetch origin"
fi
echo

# 「本分支新增」的內容行＝diff 的新增行 ＋ 未追蹤新檔的全部內容
added_lines() {  # $1 = 路徑 pattern
  { git diff "$BASE"...HEAD -- "$1"; git diff -- "$1"; git diff --cached -- "$1"; } 2>/dev/null \
    | grep -E '^\+' | grep -v '^+++' | sed 's/^+//'
  untracked_files | grep -E "$(printf '%s' "$1" | sed 's/\*\*/.*/g; s/\*/[^\/]*/g')" 2>/dev/null \
    | while IFS= read -r uf; do [ -f "$uf" ] && cat "$uf"; done
}

# ── B1. Task / Requirement 編號碰撞 ────────────────────────────────
# 真正的撞號＝分支點之後，我和 main「各自」新增了同一個編號。
# 必須以 merge-base 為基準三方比對；直接拿 HEAD 對 base 比會把「既有編號被移動位置」
# 誤判成新增（重排 tasks.md 時整份都會變成 diff 的新增行）。
MB=$(git merge-base HEAD "$BASE" 2>/dev/null)
# Task 編號空間橫跨三處，缺一就會把已用編號誤判為可用：
#   spec/tasks.md（未歸檔的尾段）、spec/tasks/archive/*.md（歷史）、spec/tasks/tNNN_*.md（新制自足任務檔）
nums_raw() {  # $1=ref（空字串＝工作區） $2=主檔 $3=正則  → 未去重的編號流
  if [ -z "$1" ]; then
    { cat "$2" 2>/dev/null
      cat spec/tasks/archive/*.md 2>/dev/null
      ls spec/tasks/t[0-9]*_*.md 2>/dev/null | xargs -n1 basename 2>/dev/null \
        | sed -E 's/^t0*([0-9]+)_.*/### Task \1: x/'; }
  else
    { git show "$1:$2" 2>/dev/null
      for a in $(git ls-tree -r --name-only "$1" -- spec/tasks/archive/ 2>/dev/null); do
        git show "$1:$a" 2>/dev/null
      done
      git ls-tree -r --name-only "$1" -- spec/tasks/ 2>/dev/null \
        | xargs -n1 basename 2>/dev/null | grep -oE '^t[0-9]+_' \
        | sed -E 's/^t0*([0-9]+)_/### Task \1: x/'; }
  fi | grep -oE "$3" | sed -E 's/^#+ (Task|Requirement) //'
}
nums_at() { nums_raw "$1" "$2" "$3" | sort -u; }
# 正則須含字母後綴，否則 Task 14 與 14b、60 與 60a–60h 會被視為同一編號而誤報重號。
TASK_PAT='^#{2,3} Task [0-9]+[a-z]?'
REQ_PAT='^### Requirement [0-9]+'
collide() {  # $1=檔案 $2=標題正則 $3=標籤
  local file="$1" pat="$2" label="$3" mine theirs n
  [ -n "$MB" ] || { check "$label 撞號檢查略過：找不到 merge-base"; return 0; }
  # 我新增的 = HEAD∪工作區 − merge-base
  mine=$(comm -23 <(nums_at "" "$file" "$pat") <(nums_at "$MB" "$file" "$pat"))
  # main 新增的 = base − merge-base
  theirs=$(comm -23 <(nums_at "$BASE" "$file" "$pat") <(nums_at "$MB" "$file" "$pat"))
  [ -z "$mine" ] && return 0
  for n in $mine; do
    if printf '%s\n' "$theirs" | grep -qx "$n"; then
      block "$label $n 撞號：分支點後我和 $BASE 各自新增了同一編號 —— 需避讓（本專案一年內 12 次）"
    fi
  done
}
collide "spec/tasks.md"        "$TASK_PAT" "Task"
collide "spec/requirements.md" "$REQ_PAT"  "Requirement"

# 重號：同一編號在編號空間內出現兩次（不論來源）。Task 39/40 曾各出現兩次，後重編為 181/182。
dupes() {  # $1=主檔 $2=正則 $3=標籤
  local d n c
  d=$(nums_raw "" "$1" "$2" | sort | uniq -d)
  for n in $d; do
    c=$(nums_raw "" "$1" "$2" | grep -cx "$n")
    block "$3 $n 重號：在編號空間內出現 $c 次（Task 39/40 有前例，需重編）"
  done
}
dupes "spec/tasks.md"        "$TASK_PAT" "Task"
dupes "spec/requirements.md" "$REQ_PAT"  "Requirement"

# ── B2/B3/B4. Liquibase changeset ─────────────────────────────────
CL_DIR="backend/src/main/resources/db/changelog"
# 只查「新增的」changeset。既有 changeset 早已跑過，改它的內容是另一回事（且多半是錯的），
# 拿冪等規則去掃全部既有檔會對 24 個歷史檔產生假 BLOCK，讓人直接放棄這支腳本。
TRACKED_SQL=$(git ls-tree -r --name-only "$BASE" -- "$CL_DIR/changes/" 2>/dev/null || true)
NEW_SQL=$(printf '%s\n' "$DIFF_FILES" | grep -E "^$CL_DIR/changes/.*\.sql$" || true)
NEW_SQL=$(printf '%s\n' "$NEW_SQL" | grep -v '^$' | while IFS= read -r f; do
  printf '%s\n' "$TRACKED_SQL" | grep -qx "$f" || printf '%s\n' "$f"
done)
if [ -n "$NEW_SQL" ]; then
  for f in $NEW_SQL; do
    ver=$(basename "$f" | grep -oE '^v[0-9]+\.[0-9]+\.[0-9]+')
    # B2 版號碰撞：base 上是否已有同版號但不同檔名
    if [ -n "$ver" ]; then
      existing=$(git ls-tree -r --name-only "$BASE" -- "$CL_DIR/changes/" 2>/dev/null \
                 | xargs -n1 basename 2>/dev/null | grep -E "^${ver}-" || true)
      if [ -n "$existing" ] && [ "$existing" != "$(basename "$f")" ]; then
        block "changeset 版號 $ver 在 $BASE 已被 $existing 佔用 —— 需改版號"
        block "  ↳ 改版號＝改 changeset id，Liquibase 視為新 migration 會重跑（Task 207 曾致整站 crash loop）"
      fi
    fi
    # B3 冪等性
    if [ -f "$f" ]; then
      if grep -qiE 'CREATE (TABLE|INDEX|UNIQUE INDEX)' "$f" && ! grep -qiE 'IF NOT EXISTS' "$f"; then
        block "$f 有 CREATE 但無 IF NOT EXISTS —— 非冪等，改號重跑會炸 already exists"
      fi
      if grep -qiE '^\s*INSERT INTO' "$f" && ! grep -qiE 'ON CONFLICT|NOT EXISTS' "$f"; then
        block "$f 有 INSERT 但無 ON CONFLICT / NOT EXISTS 守門 —— 非冪等"
      fi
    fi
    # B4 註冊
    if [ -n "$(basename "$f")" ] && ! grep -q "$(basename "$f")" "$CL_DIR/db.changelog-master.yaml" 2>/dev/null; then
      block "$(basename "$f") 未註冊進 db.changelog-master.yaml —— 永不執行"
    fi
  done
fi

# ── B5. spec 宣稱的測試類是否存在 ─────────────────────────────────
# 排除 JUnit / Spring 的框架註解——它們長得像測試類名，但不是本專案的檔案，
# 報出來無法可修，只會逼人繞過閘門。`(^|[^@A-Za-z])` 濾掉 @SpringBootTest 這種寫法。
FRAMEWORK_TESTS='^(SpringBootTest|WebMvcTest|DataJpaTest|WebFluxTest|JsonTest|RestClientTest|JdbcTest|DataRedisTest|ParameterizedTest|RepeatedTest|TestNGTest)$'
for t in $(added_lines 'spec/**' | grep -oE '(^|[^@A-Za-z])[A-Z][A-Za-z0-9]*Test\b' \
           | grep -oE '[A-Z][A-Za-z0-9]*Test' | sort -u); do
  printf '%s' "$t" | grep -qE "$FRAMEWORK_TESTS" && continue
  if ! find . -path ./.git -prune -o -name "${t}.java" -print -o -name "${t}.js" -print 2>/dev/null | grep -q .; then
    block "spec 提到測試類 $t，但全樹找不到 ${t}.java/.js —— 宣稱的驗證不存在（Task 160 前例）"
  fi
done

# ── B6. @Scheduled 變更是否同步排程登錄表 ─────────────────────────
# 只掃 .java：文件裡「提到」@Scheduled（例如本檢查自己的說明）不算改動了排程。
# 新增行與刪除行都要看——移除一個排程同樣會讓排程列表頁漂移，只看 '^+' 會漏掉整個刪除情境。
SCHED_TOUCHED=$({ git diff "$BASE"...HEAD -- '*.java'; git diff -- '*.java'; git diff --cached -- '*.java'; } 2>/dev/null \
  | grep -E '^[+-]' | grep -vE '^(\+\+\+|---)' | grep -cE '@Scheduled|cron\s*=' || true)
if [ "${SCHED_TOUCHED:-0}" -gt 0 ]; then
  if ! printf '%s\n' "$DIFF_FILES" | grep -q 'SchedulePublicBffController'; then
    block "改動了 @Scheduled/cron，但未同步 SchedulePublicBffController.JOBS —— 排程列表頁會漂移（Task 195 根因）"
  fi
  check "排程數若有增減，design.md / requirements.md 的排程總筆數也要一起改"
fi

# ── B7. 文件計數宣告 ──────────────────────────────────────────────
REQ_ACTUAL=$(grep -cE '^### Requirement [0-9]+' spec/requirements.md 2>/dev/null || echo 0)
for doc in CLAUDE.md spec/steering/structure.md; do
  [ -f "$doc" ] || continue
  claimed=$(grep -oE '[0-9]+ 個 Requirements' "$doc" 2>/dev/null | grep -oE '^[0-9]+' | head -1)
  if [ -n "$claimed" ] && [ "$claimed" != "$REQ_ACTUAL" ]; then
    block "$doc 宣稱 $claimed 個 Requirements，實際 $REQ_ACTUAL"
  fi
done

# ── B8. spec 對 DB 現況的斷言引用了 changelog（錯誤基準線）────────
if added_lines 'spec/**' | grep -qE 'db/changelog/.*\.sql'; then
  check "spec 引用了 db/changelog/*.sql —— 若用途是描述 DB 現況即為錯誤基準線（Task 148→197→201 的教訓：照永不執行的 changelog 改，反而改成與 DB 不一致）。現況一律以運行中 DB 為準：docker exec asset-postgres psql -U assets -d assets -c '\\d <table>'。注意 db/schema.sql 只是離線鏡像、已知落後（Task 241 實測缺 crawler_export_setting／asset_transaction），不可當基準線。若本次引用只是「新 changeset 放哪／現有最大版號」則屬正當用途，可忽略本項。"
fi

# ── 摘要 ──────────────────────────────────────────────────────────
# B9. Docker 外部 9090 gateway / OpenAPI 契約是 current-state 不變條件；
# 單改 Nginx allowlist、Swagger status/schema/example 或 market catalog 都必須 BLOCK。
OPENAPI_TEST='scripts/tests/docker-external-api-openapi-test.rb'
if ! command -v ruby >/dev/null 2>&1; then
  block "找不到 ruby，無法執行 9090 gateway/OpenAPI contract"
elif [ ! -f "$OPENAPI_TEST" ]; then
  block "缺 $OPENAPI_TEST，9090 gateway/OpenAPI parity 無機械防漂移"
else
  openapi_output=$(ruby "$OPENAPI_TEST" 2>&1)
  openapi_status=$?
  printf '%s\n' "$openapi_output"
  [ "$openapi_status" -eq 0 ] || block "9090 gateway/OpenAPI contract 失敗（完整訊息如上）"
fi

echo
echo "════ 結果 ════"
echo "BLOCK: $BLOCKS   CHECK: $CHECKS"
if [ "$BLOCKS" -gt 0 ]; then
  echo "→ 有已證實的缺陷，spec 不得進入實作階段。"
  exit 1
fi
echo "→ 機械檢查通過（不代表 spec 正確，仍須跑對抗式審查）。"
exit 0
