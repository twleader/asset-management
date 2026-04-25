#!/usr/bin/env bash
#
# 資產管理系統 — PostgreSQL 每日備份腳本
# 流程：docker exec pg_dump → rclone 上傳到 Google Drive (crypt 加密) → 輪替本地與遠端舊檔
#
# 排程：透過 launchd 於每日 05:00 執行（見 com.steven.asset-management.backup.plist）
# 還原驗證：見 README.md「還原驗證」段落
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

# ===== Config =====
CONTAINER="asset-postgres"

BACKUP_DIR="/Users/steven/Backups/asset-management"
REMOTE="gdrive-crypt:backups"
LOG_FILE="/Users/steven/Library/Logs/asset-management-backup.log"

RETENTION_DAILY=7      # 每日備份保留 7 天
RETENTION_WEEKLY=4     # 每週備份保留 4 週
RETENTION_MONTHLY=6    # 每月備份保留 6 個月
RETENTION_LOCAL=3      # 本地保留 3 天作為快速還原

# launchd 環境變數很乾淨，所有外部指令一律用絕對路徑
PATH="/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin"

DOCKER="/usr/local/bin/docker"
[ -x "$DOCKER" ] || DOCKER="/opt/homebrew/bin/docker"
RCLONE="/opt/homebrew/bin/rclone"

# ===== Helpers =====
log() {
    echo "[$(date '+%F %T')] $*" | tee -a "$LOG_FILE"
}

notify() {
    /usr/bin/osascript -e "display notification \"$2\" with title \"資產管理備份\" subtitle \"$1\"" || true
}

on_error() {
    log "!!! Backup FAILED at line $1 !!!"
    notify "備份失敗" "$(date +%F) 請檢查 $LOG_FILE"
    exit 1
}
trap 'on_error $LINENO' ERR

# ===== Pre-flight =====
mkdir -p "$BACKUP_DIR"
mkdir -p "$(dirname "$LOG_FILE")"

log "=== Backup start ==="

# 讀取 .env 取得 POSTGRES_USER / POSTGRES_DB
if [ ! -f "$PROJECT_DIR/.env" ]; then
    log "ERROR: $PROJECT_DIR/.env not found"
    exit 1
fi
set -a
# shellcheck disable=SC1091
source "$PROJECT_DIR/.env"
set +a

# 確認容器在跑
if ! "$DOCKER" ps --format '{{.Names}}' | grep -q "^${CONTAINER}$"; then
    log "ERROR: container $CONTAINER not running"
    exit 1
fi

# ===== Run =====
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
DOW=$(date +%u)   # 1=Mon..7=Sun
DOM=$(date +%d)

# 1) pg_dump（custom format 內建壓縮，支援 pg_restore 細粒度還原）
DUMP_FILE="$BACKUP_DIR/asset_${TIMESTAMP}.dump"
log "pg_dump → $DUMP_FILE"
"$DOCKER" exec "$CONTAINER" pg_dump \
    -U "$POSTGRES_USER" \
    -d "$POSTGRES_DB" \
    --format=custom \
    --compress=9 \
    --no-owner \
    --no-acl \
    > "$DUMP_FILE"

SIZE=$(du -h "$DUMP_FILE" | cut -f1)
log "Dump size: $SIZE"

# 2) 上傳到遠端 daily/（rclone crypt 自動加密）
log "Upload → $REMOTE/daily/"
"$RCLONE" copy "$DUMP_FILE" "$REMOTE/daily/" --log-file="$LOG_FILE" --log-level INFO

# 3) 週日多複製一份到 weekly/
if [ "$DOW" = "7" ]; then
    log "Snapshot → $REMOTE/weekly/"
    "$RCLONE" copy "$DUMP_FILE" "$REMOTE/weekly/" --log-file="$LOG_FILE"
fi

# 4) 每月 1 號多複製一份到 monthly/
if [ "$DOM" = "01" ]; then
    log "Snapshot → $REMOTE/monthly/"
    "$RCLONE" copy "$DUMP_FILE" "$REMOTE/monthly/" --log-file="$LOG_FILE"
fi

# 5) 遠端輪替（依保留策略刪舊檔）
# 首次執行時 weekly/ 與 monthly/ 可能還不存在，先確保目錄存在再刪
log "Rotate remote"
"$RCLONE" mkdir "$REMOTE/daily/"   2>/dev/null || true
"$RCLONE" mkdir "$REMOTE/weekly/"  2>/dev/null || true
"$RCLONE" mkdir "$REMOTE/monthly/" 2>/dev/null || true
"$RCLONE" delete "$REMOTE/daily/"   --min-age "${RETENTION_DAILY}d"
"$RCLONE" delete "$REMOTE/weekly/"  --min-age "$((RETENTION_WEEKLY * 7))d"
"$RCLONE" delete "$REMOTE/monthly/" --min-age "$((RETENTION_MONTHLY * 30))d"

# 6) 本地輪替
find "$BACKUP_DIR" -name "asset_*.dump" -mtime "+${RETENTION_LOCAL}" -delete

log "=== Backup done ==="
notify "備份成功" "$(date +%F) size=$SIZE"
