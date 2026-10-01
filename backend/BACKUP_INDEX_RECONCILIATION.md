# 備份索引提交結果未定時的唯讀對帳

`/home/steven/.asset-backup-pending/.backup-index-commit-in-flight` 是持久隔離標記。存在時，
create、sync、restore、保留設定與輪替都拒絕執行，以免未知的 DB commit 結果導致重複寫入或誤刪。
標記內只有操作類型及 exact 檔名／線索，沒有憑證。不能因 API 回錯就推定交易已 rollback。
若交易尚未進入 callback，或交易管理器已完成 rollback 並正常返回，系統會清除標記；
已上傳檔仍作 orphan 保留，須唯讀對帳，且不會自動重傳或刪除。

1. 保留標記及任何同目錄本地 dump。先唯讀核對正式 `.env` 中獨立釘選的 provider ID、
   host rclone config 的 crypt backing 與密碼指紋，並完整列舉 My Drive 根層及 active root 的日期目錄。
2. 依標記內的 `exact` 欄位，唯讀查詢 `backup_record` exact `(folder, filename)`、
   active root 對應日期下的 exact crypt 物件、大小及可解密 `PGDMP` header。
   對設定操作另唯讀查詢 `backup_setting`；對同步則比對完整 DATE 清單與索引。舊的
   `2026-09-29` 前索引及 `backups/` 目錄不參與對帳或刪除。
3. 確認原交易及遠端子程序都已停止，且 DB、遠端、來源檔與操作預期一致並留存唯讀查核證據後，
   才由維運者明確移除標記，接著 recreate／restart `business-services`，再重試必要的非破壞性操作。
   服務內的隔離旗標在同一容器中不會因刪除標記而清除。若缺 row 而遠端有物件，保留 orphan，
   先查明原因；不能自動重傳、
   覆寫或刪除。若任一 readback 失敗、內容不一致或 PGDMP 無法解密，保留標記並停止。

這是維運隔離機制，不代表 PostgreSQL 16 的 `COMMIT` 有可證明的整體 wall-clock 截止時間。
