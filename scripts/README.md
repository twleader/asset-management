# scripts/

| 檔案 | 用途 |
|------|------|
| `db-export.sh` / `db-import.sh` | PostgreSQL 安全匯出／清空現有 volume 後還原 |
| `git-hooks/commit-msg` | SDD 同步檢查——只驗「`spec/` 有無變更」（見 CLAUDE.md）。用 commit-msg 而非 pre-commit，是因為只有這個階段讀得到本次 commit 訊息，`[skip-spec]` 才判斷得準 |
| `spec-check.sh` | spec 變更的機械前置檢查：編號撞號／重號、Liquibase changeset 版號碰撞與冪等性、宣稱的測試類是否存在、文件計數漂移、spec 是否拿 `db/changelog/*.sql` 當 DB 現況基準線，並執行 9090 gateway/OpenAPI 防漂移契約與 `db/schema.sql` 防漂移契約（B10）。實作前搭配 `/spec-review` 使用 |
| `configure-tailscale-api-gateway.sh` | 十二路本機 API 的 status／Content-Type／payload preflight、Serve 所有權與 TOCTOU 檢查通過後，才設定十二條 path-scoped Tailscale HTTPS handler（十一 GET、唯一 POST） |
| `tests/configure-tailscale-api-gateway-test.sh` | 以假的 curl／Tailscale CLI 驗證所有 public GET（含 quotes、雷達 list/detail、交易紀錄、交易日曆）的 `200 text/plain` 在 reset 前 fail closed，並驗正常路徑只設定十二條 handler |
| `render-9090-openapi-docs.rb` | 只讀 `docs/openapi/docker-external-api.yaml`，決定性產生 worktree 與 SRPP 的兩份位元組一致 9090 Swagger Markdown；`--check` 只驗證是否已同步 |
| `tests/docker-external-api-openapi-test.rb` | 只用 Ruby stdlib YAML 驗證 9090 Nginx exact path+method 與 OpenAPI 十二路雙向相等、response status manifest、parameters、schemas、examples、完整 attribute descriptions 與 local refs；可直接執行 `ruby scripts/tests/docker-external-api-openapi-test.rb` |
| `tests/schema-sql-drift-test.sh` | 驗證 `db/schema.sql`（去除專案檔頭後）逐位元等於此刻 `asset-postgres` 的 `pg_dump --schema-only` 輸出，並離線檢查檔頭「產生當下表數：N 張」宣告；離開碼三態 `0` 同步／`1` 已證實漂移／`2` 無法查證（docker 不可用或容器未運行），由 `spec-check.sh` 的 B10 依離開碼與本次是否碰到 schema／changelog 分流 BLOCK 或 CHECK。容器名可用 `SCHEMA_DRIFT_CONTAINER` 覆寫 |

一般使用者安裝、首次啟動、日常維運與發行前檢查請見 [`../INSTALLATION.md`](../INSTALLATION.md)。

全新安裝會由 Liquibase 建立空白資料庫，不需要 `db-import.sh` 或任何人的資料 dump。

> `db/init/01_dump.sql` 含真實個人財務資料，已被 `.gitignore` 排除，不可強制加入 Git。
> `db-import.sh` 會先執行 `docker compose down -v`，只可在確認現有資料可刪除、且安全 dump 已備妥時使用。

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
