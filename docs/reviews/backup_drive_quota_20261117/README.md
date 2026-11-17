# 2026-11-17 Google Drive 備份配額故障處理

所有時間均為 Asia/Taipei。本次為維運設定修復與既有備份來源補傳，沒有修改程式、排程、保留代數或資料庫 schema。

## 已確認的故障

- `backup_setting.backup_enabled=true`；修復前最新 `backup_record` 仍為 2026-11-14 12:11:06。
- 週一原始來源 `asset_daily_tw_20261116_153000_10256421424369105926.dump` 仍存在於私有 pending 目錄，大小 398,951,151 bytes；`pg_restore --list` 顯示 archive 建立於 2026-11-16 15:30:00。另有 23:11:48 的同日補救來源。因此週一排程已執行，缺漏發生於 dump 之後。
- 2026-11-15、16、17 的 active crypt 日期目錄完整列舉均為空，缺漏不限於畫面索引。
- 修復前 host 及實際 business 容器的 `GoogleDriver:` 均以 rclone 共用 OAuth client 運作。2026-11-17 19:16:19 的最小唯讀診斷得到 HTTP 403、`rateLimitExceeded`／`RATE_LIMIT_EXCEEDED`，配額為共用專案 `202264815644` 的 Drive `Queries`、`defaultPerMinutePerProject`，上限 840,000 次／分鐘。
- 同時，既有自有 OAuth client 的 `GDriveOutput:` 可正常查詢；Drive 可用空間約 6.94 GB，排除空間不足。
- 當時 19:10:40 啟動的 TW recovery 已完成 425,139,308-byte dump，卻長時間停留在 rclone upload。19:18:35 單獨終止已確認的 rclone PID 220，Java 沒有重啟；既有失敗路徑保留來源並釋放工作鎖，接著 US recovery 自動開始。

歷史容器日誌已隨先前 recreate 消失，不能聲稱讀到了週一當時的 403。週一原始 dump、缺少遠端物件、目前可重現的共用 client 配額故障，以及修復後同一路徑的驗收，是本次判斷的依據。

`xattrs not supported` 曾出現在容器 rclone stderr；以相同來源掛載的小檔對照試驗，含與不含 `--metadata` 均 exit 0，因此沒有把這條訊息誤認為主因，也沒有為此改動 metadata 或 nonce 防護。

## 設定修復與目的地驗證

1. 私下保存原始 host config，備份目錄 0700、檔案 0600。憑證與完整 config 不加入版本控制。
2. 自有 client 驗得 My Drive 根層唯一 `asset-management-backup`，ID 與實際容器的 `BACKUP_DRIVE_FOLDER_ID` 完全一致；該資料夾 `ownedByMe=true` 且只有一位 owner。
3. 候選配置沿用原 `gdrive-crypt` 全部設定，成功解密既有 `2026-11-14/asset_daily_us_20261114_121106_7163536075627190843.dump` 前五 bytes 為 `PGDMP`，遠端大小 328,907,503 bytes 與原索引一致。
4. 本次原子安裝 host 設定，只替換 `GoogleDriver` 的 `client_id`、`client_secret`、`token` 三個鍵，採用既有自有 client 的授權。scope、crypt backing、加密密碼及部署釘選均保持原樣；設定檔權限 0600。
5. 由既有 source fingerprint reload 機制載入；沒有延長原有 720 秒子程序／900 秒 workflow deadline。

原共用 client 的 user 查詢被配額拒絕，因此沒有宣稱完成兩份授權的雙向帳號 ID 比對；已驗證的是正確目的地的所有權、獨立釘選 ID，以及原 archive 的可解密性。

## 原始來源完整性

以下來源均經 `pg_restore --file=/dev/null` 完整讀取成功（exit 0），沒有對資料庫執行還原。

| 來源 | bytes | SHA-256 |
|---|---:|---|
| `asset_weekly_20261115_050000_2922640593744357766.dump` | 328705809 | `eb92a4140566aaf9887a0812eba307466524b00d67161f46cdf6336fc6db973f` |
| `asset_daily_tw_20261116_153000_10256421424369105926.dump` | 398951151 | `9326685b0d78457c38b3a1c2ae78960b18c61243473556cd4ccdbd8a0e21e148` |
| `asset_daily_us_20261117_070000_4278815509569516054.dump` | 353098817 | `3475a122cee264b9e5aa55ed02b8065a4845b29e6ba08e920036d20753110639` |
| `asset_daily_tw_20261117_152959_15327992234669725541.dump` | 425050262 | `e59786e22de3b65bb59b28ba8fca53df022f7b861c991e93b0ee1d90342c9daa` |

## 執行與驗收紀錄

- 原服務的 US startup recovery 自動接續，19:19:38 完成 dump，19:20:16 出現 `Backup uploaded and indexed`：`asset_daily_us_20261117_191835_3526904505024532962.dump`，425,139,413 bytes，`backup_record.id=263`。上傳與逐檔驗證約 38 秒。
- 唯讀比對容器 `/tmp/rclone.conf` 與 host source：自有 client ID／secret 相符、crypt 完整相符；crypt password fingerprint 與實際 `BACKUP_CRYPT_CONFIG_SHA256` 相符。證實運行中的服務已自動 reload，沒有 recreate。
- 週一 15:30 原始來源經完整遠端列舉確認不存在後，以 `--ignore-existing`、獨立 nonce 及單次 retry 補傳，exit 0，耗時 32.43 秒、約 12.4 MiB/s。19:21:37 `rclone cryptcheck` exit 0，`0 differences found`／`1 matching files`；主 agent 另於 19:22:01 獨立重驗同樣成功。
- 第一次既有 ADMIN `POST /api/backups/sync` 回 `{"inserted":1,"deleted":0,"total":17}`。DB 逐檔讀回週一 `id=264`、size=398951151、`modified_at=2026-11-16 15:30:56.756`、`created_at=2026-11-17 19:22:28.988025`。前者保持原 dump 完成時間，後者誠實記錄補建索引時間；沒有把今日資料偽裝為週一 snapshot。
- US success 後，原程式依既有 daily retention=10 執行輪替，刪除最舊 11/09 daily；本次沒有調整保留設定。補傳來源及其他未完成來源均保留於私有 pending 目錄，未批次清除。
- 週日 05:00 weekly 原始來源補傳 exit 0、24.37 秒，19:23:05 cryptcheck exit 0、0 differences／1 matching files。今日 15:30 TW 原始來源補傳 exit 0、36.89 秒，19:23:46 cryptcheck exit 0、0 differences／1 matching files。補傳均先完整列舉確認 exact 檔名不存在、使用 ignore-existing 與獨立 nonce，沒有覆寫舊物件。
- 最後既有 ADMIN sync 回 `{"inserted":2,"deleted":0,"total":19}`，19:24:37 完成。`GET /api/backups` 與 DB 均讀回以下四份，來源、大小及原時間一致；business 容器仍為 running／healthy。

| record ID | 來源 | bytes | 台北備份時間 |
|---:|---|---:|---|
| 265 | 11/15 weekly 原始 dump | 328705809 | 2026-11-15 05:00:42.850 |
| 264 | 11/16 TW 原始 dump | 398951151 | 2026-11-16 15:30:56.756 |
| 266 | 11/17 TW 原始 dump | 425050262 | 2026-11-17 15:31:02.334 |
| 263 | 11/17 US 當日 startup recovery | 425139413 | 2026-11-17 19:18:35.857412 |

同步只重建索引，依既有契約不執行 retention pruning；後續排程成功時沿原設定輪替。本次未直接新增／修改／刪除 DB row，索引均經既有服務 workflow 寫入。

## 再驗與回復方式

不含憑證的週一完整性再驗命令：

```bash
rclone cryptcheck /Users/steven/.asset-backup-pending gdrive-crypt:2026-11-16/ \
  --include /asset_daily_tw_20261116_153000_10256421424369105926.dump --one-way
```

私有原設定備份：`/Users/steven/.config/rclone/backups/backup-quota-20261117-191744/rclone.conf.before`。若需回復，先確認沒有備份 workflow 正在運行，再以 0600 暫存檔原子替換 host `rclone.conf`，保留 crypt 與 pins，並讓下一個 workflow 依 source fingerprint reload；舊共用 client 的配額問題仍可能存在，回復不代表配額故障消失。不可把設定內容貼入日誌／文件／Git。

本次已實測原服務成功建立、上傳及提交索引，並驗證補傳檔的加密完整性。未來的 cron 尚未發生，沒有將本次 startup recovery 當成下一次定時執行的證明；週一早上本來就沒有 US daily（美股每日備份在台北週二至週六 07:00）。
