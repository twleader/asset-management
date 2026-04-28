# scripts/

| 檔案 | 用途 |
|------|------|
| `db-export.sh` / `db-import.sh` | 開發用 PostgreSQL dump / 還原 |
| `git-hooks/pre-commit` | SDD 同步檢查（見 CLAUDE.md） |

## 備份排程

PostgreSQL → Google Drive（rclone crypt）的自動備份**已全面改由 Spring Boot `BackupService` 排程**，
不再走 host launchd / shell script。三條規則：

| 排程 | cron | 對應檔名前綴 |
|------|------|--------------|
| 台股交易日盤後 +2h | `0 30 15 * * MON-FRI`（Asia/Taipei） | `asset_daily_tw_` |
| 美股交易日盤後 +2h | `0 0 7 * * TUE-SAT`（Asia/Taipei） | `asset_daily_us_` |
| 每週日 05:00 | `0 0 5 * * SUN`（Asia/Taipei） | `asset_weekly_` |

UI：`/backups`（前端「備份/還原 資料」頁）。手動備份／還原也走同一支 `BackupController`。

## 一次性 host 設定（rclone）

backend 容器內已裝 `rclone` 並掛載 `${HOME}/.config/rclone/rclone.conf:ro`，
host 端只需設好 `gdrive-crypt` remote：

```bash
brew install rclone
rclone config   # 建立 GoogleDriver (drive.file scope) + gdrive-crypt (crypt) 兩個 remote
rclone lsd gdrive-crypt:   # 驗證
```

> rclone crypt 密碼遺失即無法解密既有備份，務必另存於密碼管理器 + 紙本。
