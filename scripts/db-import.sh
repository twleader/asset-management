#!/usr/bin/env bash
# db-import.sh — 在新機器上重建容器並匯入 db/init/01_dump.sql
# 用法：./scripts/db-import.sh
# ⚠️  會刪除現有的 PostgreSQL volume，舊資料將消失。

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

cd "$PROJECT_DIR"

# 確認 .env 存在
if [ ! -f ".env" ]; then
  echo "❌ 找不到 .env 檔案！"
  echo "   請複製 .env.example 並填入密碼："
  echo "   cp .env.example .env"
  echo "   然後編輯 .env 填入正確的 POSTGRES_PASSWORD"
  exit 1
fi

# 確認 dump 檔存在
if [ ! -f "db/init/01_dump.sql" ]; then
  echo "❌ 找不到 db/init/01_dump.sql，請先在來源機器執行 scripts/db-export.sh"
  exit 1
fi

echo "⚠️  此操作將刪除現有的 PostgreSQL volume 並重新匯入資料。"
read -r -p "確定繼續？(y/N) " confirm
[[ "$confirm" =~ ^[Yy]$ ]] || { echo "已取消"; exit 0; }

echo "▶ 停止並移除舊容器與 volume..."
docker compose down -v

echo "▶ 重新啟動（PostgreSQL 會自動執行 db/init/01_dump.sql）..."
docker compose up -d

echo "▶ 等待系統啟動（最多 90 秒）..."
for i in $(seq 1 30); do
  if docker ps --filter "name=asset-backend" --filter "health=healthy" --format "{{.Names}}" | grep -q asset-backend; then
    echo ""
    echo "✅ 匯入完成，系統已啟動：http://localhost"
    exit 0
  fi
  printf "."
  sleep 3
done

echo ""
echo "⚠️  後端尚未健康，請稍後確認："
echo "   docker logs asset-backend"
