#!/usr/bin/env bash
# db-export.sh — 匯出目前 PostgreSQL 資料到 db/init/01_dump.sql
# 用法：./scripts/db-export.sh
# 匯出後請 git commit + push，新機器 pull 後執行 db-import.sh 即可同步資料。

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

# 讀取 .env
set -a
source "$PROJECT_DIR/.env"
set +a

DUMP_FILE="$PROJECT_DIR/db/init/01_dump.sql"
CONTAINER="asset-postgres"

echo "▶ 開始匯出資料庫..."

# 確認容器正在運行
if ! docker ps --format '{{.Names}}' | grep -q "^${CONTAINER}$"; then
  echo "❌ 容器 ${CONTAINER} 未在運行，請先啟動：docker compose up -d postgres"
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
  > "$DUMP_FILE"

SIZE=$(du -sh "$DUMP_FILE" | cut -f1)
echo "✅ 匯出完成：$DUMP_FILE（$SIZE）"
echo ""
echo "接下來步驟："
echo "  git add db/init/01_dump.sql"
echo "  git commit -m 'chore: update database dump'"
echo "  git push"
