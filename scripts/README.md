# 備份腳本說明

每日 05:00 自動將 PostgreSQL 資料庫備份到 Google Drive（透過 rclone crypt 加密上傳）。
PostgreSQL 跑在 Docker 容器 `asset-postgres` 內，腳本透過 `docker exec pg_dump` 取得備份。

## 檔案

| 檔案 | 用途 |
|------|------|
| `backup.sh` | 主備份腳本（docker exec pg_dump → rclone → 輪替） |
| `com.steven.asset-management.backup.plist` | launchd 排程設定（每日 05:00） |
| `db-export.sh` / `db-import.sh` | 既有的開發用 dump/還原工具（與本備份獨立） |

## 一次性安裝步驟

### 1. 安裝 rclone

```bash
brew install rclone
which rclone   # 預期：/opt/homebrew/bin/rclone
```

註：不需安裝 `pg_dump`，腳本會用 `docker exec` 在容器內執行。

### 2. 設定 rclone（Google Drive + 加密）

```bash
rclone config
```

第一個 remote — Google Drive：
- `n` (new) → name: `gdrive` → storage: `drive`
- 走預設 OAuth（會開瀏覽器登入授權）
- scope 選 `drive.file`（只能存取本工具建立的檔案，較安全）
- 完成後到 Google Drive 網頁手動建立資料夾 `asset-management-backup`

第二個 remote — 加密層：
- `n` (new) → name: `gdrive-crypt` → storage: `crypt`
- remote: `gdrive:asset-management-backup`
- filename_encryption: `standard`
- directory_name_encryption: `true`
- 設一組強密碼（**必須另存於密碼管理器 + 紙本，遺失等於備份報廢**）
- second password (salt) 留空或自訂

測試：
```bash
rclone lsd gdrive-crypt:
echo "test" | rclone rcat gdrive-crypt:test.txt
rclone cat gdrive-crypt:test.txt    # 應印出 test
rclone delete gdrive-crypt:test.txt
```

### 3. 安裝 launchd 排程

```bash
# 複製 plist 到 LaunchAgents
cp /Users/steven/Project/asset-management/scripts/com.steven.asset-management.backup.plist \
   ~/Library/LaunchAgents/

# 載入
launchctl load ~/Library/LaunchAgents/com.steven.asset-management.backup.plist

# 確認已註冊
launchctl list | grep asset-management
```

### 4. 設定 Mac 自動喚醒（重要）

```bash
# 每天 04:55 自動喚醒（需接電源）
sudo pmset repeat wakeorpoweron MTWRFSU 04:55:00

# 確認
pmset -g sched
```

注意：
- `wakeorpoweron` 需 Mac 接電源才生效
- Mac 完全關機狀態無法自動開機（macOS 限制）
- 筆電合上蓋且接電源時可以醒來執行排程

### 5. 首次手動測試

**前提：Docker 容器 `asset-postgres` 必須在跑**（`docker compose up -d`）。

```bash
chmod 700 /Users/steven/Project/asset-management/scripts/backup.sh

# 直接執行（看完整流程）
bash -x /Users/steven/Project/asset-management/scripts/backup.sh

# 或透過 launchd 立即觸發一次
launchctl start com.steven.asset-management.backup

# 看 log
tail -f /Users/steven/Library/Logs/asset-management-backup.log
```

到 Google Drive 網頁的 `asset-management-backup/` 應看到加密檔（檔名是亂碼，正常）。

## 還原驗證（建議每月一次）

```bash
# 從 Google Drive 抓最新備份
rclone copy "gdrive-crypt:backups/daily/" /tmp/restore-test/ \
    --include "asset_$(date +%Y%m)*.dump" --max-age 2d

# 還原到容器內的測試 DB（透過 docker exec）
DUMP=$(ls -t /tmp/restore-test/asset_*.dump | head -1)
docker exec asset-postgres createdb -U "$POSTGRES_USER" asset_restore_test
docker exec -i asset-postgres pg_restore -U "$POSTGRES_USER" -d asset_restore_test < "$DUMP"

# 抽查筆數
docker exec asset-postgres psql -U "$POSTGRES_USER" -d asset_restore_test \
    -c "SELECT COUNT(*) FROM stock;"

# 清理
docker exec asset-postgres dropdb -U "$POSTGRES_USER" asset_restore_test
rm -rf /tmp/restore-test
```

**沒驗證過的備份不算備份。**

## 操作指令備忘

```bash
# 停用排程
launchctl unload ~/Library/LaunchAgents/com.steven.asset-management.backup.plist

# 重新載入（修改 plist 後）
launchctl unload ~/Library/LaunchAgents/com.steven.asset-management.backup.plist
launchctl load   ~/Library/LaunchAgents/com.steven.asset-management.backup.plist

# 立即執行一次
launchctl start com.steven.asset-management.backup

# 看 log
tail -f /Users/steven/Library/Logs/asset-management-backup.log

# 列出遠端備份
rclone ls gdrive-crypt:backups/daily/
rclone ls gdrive-crypt:backups/weekly/
rclone ls gdrive-crypt:backups/monthly/

# 取消 Mac 自動喚醒
sudo pmset repeat cancel
```

## 保留策略

| 層級 | 保留數量 | 觸發條件 |
|------|---------|---------|
| daily | 7 天 | 每天 |
| weekly | 4 週 | 週日 |
| monthly | 6 個月 | 每月 1 號 |
| 本地 | 3 天 | 每次備份後輪替 |

修改於 `backup.sh` 上方 `RETENTION_*` 變數。

## 安全注意事項

- `.env`（含 `POSTGRES_PASSWORD`）已在 `.gitignore`，**絕對不可 commit**
- `~/.config/rclone/rclone.conf` 在 repo 之外，安全
- rclone crypt 密碼 **遺失無法復原**，務必另存於密碼管理器 + 紙本
- Google Drive 個人帳號免費 15GB，定期用 `rclone about gdrive:` 查容量
