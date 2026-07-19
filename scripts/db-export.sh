#!/usr/bin/env bash
# db-export.sh — 匯出目前 PostgreSQL 資料到 db/init/01_dump.sql
# 用法：./scripts/db-export.sh
# 匯出檔含真實財務資料，已被 .gitignore 排除；請用加密媒體安全搬移，不可 commit / push。

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

# 讀取 .env
set -a
source "$PROJECT_DIR/.env"
set +a

DUMP_FILE="$PROJECT_DIR/db/init/01_dump.sql"
CONTAINER="asset-postgres"

mkdir -p "$PROJECT_DIR/db/init"
TEMP_DUMP="$(mktemp "$PROJECT_DIR/db/init/01_dump.sql.tmp.XXXXXX")"
cleanup() {
  rm -f "$TEMP_DUMP"
}
trap cleanup EXIT

echo "▶ 開始匯出資料庫..."

# 確認容器正在運行
if ! docker ps --format '{{.Names}}' | grep -q "^${CONTAINER}$"; then
  echo "❌ 容器 ${CONTAINER} 未在運行，請先啟動：docker compose -p asset-management up -d postgres"
  exit 1
fi

# 完整 dump（schema + data），含 databasechangelog
# 讓新機器的 Liquibase 啟動時看到 changeset 已執行，不重複跑
docker exec "$CONTAINER" pg_dump \
  -U "$POSTGRES_USER" \
  -d "$POSTGRES_DB" \
  --no-owner \
  --no-acl \
  --clean \
  --if-exists \
  > "$TEMP_DUMP"

# 只有完整匯出成功才取代上一份 dump，避免失敗時留下空檔或半成品。
mv "$TEMP_DUMP" "$DUMP_FILE"
trap - EXIT

SIZE=$(du -sh "$DUMP_FILE" | cut -f1)
echo "✅ 匯出完成：$DUMP_FILE（$SIZE）"
echo ""
echo "接下來步驟："
echo "  1. 用 shasum -a 256 db/init/01_dump.sql 記錄檔案指紋"
echo "  2. 將 dump 放入加密媒體，安全複製到新電腦"
echo "  3. 不可 git add -f、commit 或 push（檔案含真實個人財務資料）"
