#!/usr/bin/env bash
#
# schema-sql-drift-test — db/schema.sql 稽核基準線防漂移契約（Requirement 103／Task 367）
#
# 語意：「db/schema.sql 去除專案檔頭後，是否逐位元等於此刻重跑 pg_dump 的輸出」。
#        必須是全文比對，不是只比表名——實測漂移大量發生在欄位、預設值、CHECK 與索引定義上，
#        只比表名會漏掉 commodity_price_history 四欄、stock_dividend_history 的 source 位數
#        與 uk_dividend_event 欄數這一整類。
#
# 離開碼（三態，供 scripts/spec-check.sh 的 B10 依嚴重度分流）：
#   0  同步
#   1  已證實漂移：本體與 pg_dump 輸出不同／檔頭結構被破壞／檔頭表數宣告不符或缺該行
#   2  無法查證：docker 指令不存在、容器未運行、或 pg_dump 失敗
#
# 用法：
#   bash scripts/tests/schema-sql-drift-test.sh
#   SCHEMA_DRIFT_CONTAINER=<容器名> bash scripts/tests/schema-sql-drift-test.sh
#
# 本腳本刻意不用 `set -e`：
#   * docker／pg_dump 失敗必須顯式轉成離開碼 2（「無法查證」）。若讓 set -e 以 docker 自己的
#     離開碼（1 或 125）結束，B10 的分流會把它誤記為 BLOCK。
#   * diff／cmp 有差異時回 1 是正常結果，不是錯誤，一律顯式判斷。
#
# 檔頭表數（367.5）是純離線檢查，刻意排在任何 docker 呼叫之前並在失敗時直接 exit 1，
# 否則沒開 Docker 時會先被離開碼 2 蓋掉，這個分支永遠測不到。

set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
schema_file="$repo_root/db/schema.sql"
schema_rel='db/schema.sql'
container="${SCHEMA_DRIFT_CONTAINER:-asset-postgres}"
db_user="${SCHEMA_DRIFT_DB_USER:-assets}"
db_name="${SCHEMA_DRIFT_DB_NAME:-assets}"

# 檔尾另有「-- PostgreSQL database dump complete」，因此定位必須用「整行相等」而非 substring。
DUMP_MARKER='-- PostgreSQL database dump'

tmpdir="$(mktemp -d)"
cleanup() { rm -rf "$tmpdir"; }
trap cleanup EXIT

print_regen() {
  cat <<REGEN
重產指令（在 repo 根目錄執行）：
  docker exec ${container} pg_dump -U ${db_user} -d ${db_name} \\
    --schema-only --no-owner --no-privileges \\
    | grep -v '^\\\\restrict\\|^\\\\unrestrict' > /tmp/fresh-schema.sql
  # 再把 ${schema_rel} 第 1 行至「${DUMP_MARKER}」前一行的專案檔頭，接到
  # /tmp/fresh-schema.sql 之前寫回 ${schema_rel}：
  #   head -n <本體起點-1> ${schema_rel} > /tmp/schema-header.sql
  #   cat /tmp/schema-header.sql /tmp/fresh-schema.sql > ${schema_rel}
  # 最後把檔頭的「產生當下表數：N 張」更新成
  #   grep -c '^CREATE TABLE' ${schema_rel}
  # 的實際值（本腳本會離線驗證這一項）。
REGEN
}

list_tables() {  # $1 = sql 檔
  grep -oE '^CREATE TABLE [A-Za-z0-9_."]+' "$1" 2>/dev/null | sed 's/^CREATE TABLE //' | sort -u
}

# ── 0. 檔案存在 ────────────────────────────────────────────────────
if [ ! -f "$schema_file" ]; then
  echo "FAIL: 找不到 ${schema_rel} —— 稽核基準線遺失"
  exit 1
fi

# ── 1. 檔頭／pg_dump 本體的分界（不得寫死行號）────────────────────
marker_line="$(grep -nxF -- "$DUMP_MARKER" "$schema_file" | head -1 | cut -d: -f1)"
if [ -z "$marker_line" ]; then
  echo "FAIL: ${schema_rel} 找不到整行等於「${DUMP_MARKER}」的行 —— 檔頭結構已被破壞，無法切出 pg_dump 本體"
  echo "      預期結構：專案檔頭 → 「--」 → 「$DUMP_MARKER」 → 「--」 → pg_dump 本體"
  print_regen
  exit 1
fi
body_start=$((marker_line - 1))
if [ "$body_start" -lt 1 ]; then
  echo "FAIL: ${schema_rel} 的「${DUMP_MARKER}」出現在第 1 行，前面沒有專案檔頭 —— 檔頭結構已被破壞"
  print_regen
  exit 1
fi
prev_line="$(sed -n "${body_start}p" "$schema_file")"
if [ "$prev_line" != "--" ]; then
  echo "FAIL: ${schema_rel} 第 ${body_start} 行（「${DUMP_MARKER}」的前一行）應恰為「--」，實際為：${prev_line}"
  echo "      —— 檔頭結構已被破壞，無法可靠切出 pg_dump 本體"
  print_regen
  exit 1
fi

# ── 2. 檔頭宣告的表數（純離線檢查，必須排在任何 docker 呼叫之前）──
header_file="$tmpdir/header.sql"
body_file="$tmpdir/body.sql"
head -n "$((body_start - 1))" "$schema_file" > "$header_file"
tail -n "+${body_start}" "$schema_file" > "$body_file"

actual_tables="$(grep -c '^CREATE TABLE' "$schema_file")"
declared_tables="$(grep -oE '產生當下表數：[0-9]+ 張' "$header_file" | grep -oE '[0-9]+' | head -1)"
if [ -z "$declared_tables" ]; then
  echo "FAIL: ${schema_rel} 檔頭缺「產生當下表數：N 張」宣告行，請依 Task 367.2 補上"
  echo "      實際 CREATE TABLE 張數：${actual_tables}"
  echo "      （此為離線檢查，與運行中 DB 無關）"
  print_regen
  exit 1
fi
if [ "$declared_tables" != "$actual_tables" ]; then
  echo "FAIL: ${schema_rel} 檔頭宣告「產生當下表數：${declared_tables} 張」，但檔案實際有 ${actual_tables} 個 CREATE TABLE"
  echo "      —— 檔案疑似被手改而未重產。此為離線檢查，本項失敗並不代表與運行中 DB 不一致。"
  print_regen
  exit 1
fi

# ── 3. 取此刻運行中 DB 的 pg_dump（失敗一律轉成離開碼 2）──────────
if ! command -v docker >/dev/null 2>&1; then
  echo "SKIP: 找不到 docker 指令 —— ${schema_rel} 是否漂移「無法查證」"
  exit 2
fi
running="$(docker inspect -f '{{.State.Running}}' "$container" 2>/dev/null)"
if [ "$running" != "true" ]; then
  echo "SKIP: 容器 ${container} 不在運行（docker inspect 回「${running:-查無此容器}」）—— ${schema_rel} 是否漂移「無法查證」"
  echo "      可用 SCHEMA_DRIFT_CONTAINER 覆寫容器名。"
  exit 2
fi

raw_dump="$tmpdir/raw-dump.sql"
dump_err="$tmpdir/dump.err"
docker exec "$container" pg_dump -U "$db_user" -d "$db_name" \
  --schema-only --no-owner --no-privileges > "$raw_dump" 2>"$dump_err"
dump_status=$?
if [ "$dump_status" -ne 0 ]; then
  echo "SKIP: 在 ${container} 執行 pg_dump 失敗（離開碼 ${dump_status}）—— ${schema_rel} 是否漂移「無法查證」"
  sed 's/^/      /' "$dump_err"
  exit 2
fi
if [ ! -s "$raw_dump" ]; then
  echo "SKIP: 在 ${container} 執行 pg_dump 得到空輸出 —— ${schema_rel} 是否漂移「無法查證」"
  exit 2
fi

# pg_dump 16 會輸出兩行帶隨機 token 的 \restrict / \unrestrict，每次執行皆不同；
# 不濾掉的話每次重產都會出現假 diff。grep 樣式的反斜線必須寫成 \\。
fresh_dump="$tmpdir/fresh-dump.sql"
grep -v '^\\restrict\|^\\unrestrict' "$raw_dump" > "$fresh_dump" || true

# ── 4. 逐位元全文比對 ─────────────────────────────────────────────
if cmp -s "$body_file" "$fresh_dump"; then
  echo "PASS: ${schema_rel}（去除專案檔頭後）逐位元等於 ${container} 此刻的 pg_dump 輸出"
  echo "      表數：${actual_tables} 張（檔頭宣告一致）"
  exit 0
fi

echo "FAIL: $schema_rel 與運行中 DB（${container}）的 schema 不一致 —— 稽核基準線已漂移"
echo

added="$(diff "$body_file" "$fresh_dump" | grep -c '^>' || true)"
removed="$(diff "$body_file" "$fresh_dump" | grep -c '^<' || true)"
echo "  差異行數：新增 ${added:-0} 行、刪除 ${removed:-0} 行（相對現行 ${schema_rel}）"

only_db="$(comm -13 <(list_tables "$body_file") <(list_tables "$fresh_dump"))"
only_file="$(comm -23 <(list_tables "$body_file") <(list_tables "$fresh_dump"))"
if [ -n "$only_db" ]; then
  echo "  僅存在於運行中 DB（${schema_rel} 缺這些表）："
  printf '%s\n' "$only_db" | sed 's/^/    - /'
else
  echo "  僅存在於運行中 DB：（無）"
fi
if [ -n "$only_file" ]; then
  echo "  僅存在於 ${schema_rel}（運行中 DB 沒有這些表）："
  printf '%s\n' "$only_file" | sed 's/^/    - /'
else
  echo "  僅存在於 ${schema_rel}：（無）"
fi
echo "  （表名兩個方向都相同時，漂移在欄位／預設值／CHECK／索引定義上——本檢查是全文比對）"

# pg_dump／server 版本註解行的特別處理：postgres 映像小版本升級（16.14→16.15）會讓
# schema 一字未改也判為漂移。刻意不濾掉這兩行（會失去版本可追溯性），但要明示成因。
non_version_changes="$(diff "$body_file" "$fresh_dump" | grep -E '^[<>]' \
  | sed -E 's/^[<>] ?//' \
  | grep -vE '^-- Dumped (from database|by pg_dump) version ' | grep -c . || true)"
if [ "${non_version_changes:-0}" -eq 0 ]; then
  echo
  echo "  ※ 差異僅在 pg_dump／server 版本註解行（-- Dumped from database version / -- Dumped by pg_dump version），"
  echo "     成因為 postgres 映像升級而非 schema 變更，重產即可。"
fi

echo
echo "  ※ ${schema_rel} 是 DB schema 的唯一標準，不一致代表「本檔已過期」，依下列指令重產即可。"
print_regen
exit 1
