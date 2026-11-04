# [t544] 富邦庫存成本均價同步資料庫與 Redis

**對應 Requirements:** Requirements 90／154／179（富邦庫存來源成本覆寫、成交不重複加計與手動存檔一致性）
**前置任務:** t436／t479
**Liquibase changeset:** 無

## 背景

富邦的庫存同步已取得每檔 `stockCode`、`shares`、`costPrice`；目前 writer 卻保留舊 `investment_cost`，首次列以零建立並等待成交 projection。2026-11-04 009826 庫存已由 1,000 變成 2,000 股，但 filled_history 回「此 API KEY 未授權該功能」，新增買入成本未更新；畫面成本 20,120 是使用者手動修正後的值。使用者明確要求以已回傳均價更新 DB 與 Redis。本任務取代富邦舊保留成本／首次零值／成交累加設計，不改其他券商、歷史資料或券商權限。

現況 snapshot detail 每次從 DB 讀取，沒有既有持股成本 Redis cache；`price:*` 是 global 行情，不能混入私人成本。DB `stock_holding.investment_cost` 為 `NUMERIC(20,2)` 的唯一總成本、shares 為 `NUMERIC(15,5)`，不新增冗餘均價欄位或 schema migration。

## 要做什麼

- [x] **544.1 來源成本原子覆寫。** `FubonInventoryWriter` 對每筆通過現有帳戶／owner／當日／inventory-unrealized quantity／行情驗證的富邦台股庫存，以未先 round 的 `costPrice × shares`、最後 `HALF_UP` 至二位寫 `investmentCost`，復用 `checkedMoney` 的 precision≤20 gate；任何溢位 rollback 整批。既有／首次列、同股數均價異動與重跑皆如此；聚合沿共用 calculator 同交易更新。保留現有 transactionDate／displayOrder／dividend metadata 與非富邦列，不能用行情／缺失成交替代成本。
- [x] **544.2 停止富邦成交成本重複投影。** `FubonTradeWriter` 保留只寫真成交與 duplicate 去重，但停止建立／呼叫新 cost projection；inventory 不再 `consumePendingBuys`。原 projection 資料與 APPLIED／PENDING／SKIPPED 狀態凍結不變，不新增 status／migration、不重放／刪改歷史。generic projector 不作全域重寫。成交與庫存先後、同時競爭、日後補匯及重跑皆不得在來源成本上另加買入款。
- [x] **544.3 小範圍 owner 成本 cache。** 新增 `HoldingCostCachePort` 與 Redis adapter，DTO 為不可變 record：固定 schemaVersion=1、ownerId、snapshotId、snapshotDate、全部富邦台股 TWD entries；entry 欄位順序為 holdingId、brokerId、stockCode、market、currency、shares、investmentCost，依 `(brokerId,stockCode,holdingId)` 排序。decimal 正規化為 stripTrailingZeros/toPlainString，零為0；固定 root／entry 欄位順序的無空白 UTF-8 canonical JSON 作 SHA-256 小寫十六進位。key=`fubon:holding-cost:{ownerId}:{snapshotId}:{sha256}`，value=同一 JSON，TTL=36小時，只有該私有scope，不改 price:*、global行情、immutable radar／SRPP快照，不使用KEYS／FLUSHDB或廣泛刪除。
- [x] **544.4 僅提交後更新。** writer saveAndFlush 後於交易內捕捉已持有 DB holdingId 的完整不可變 DTO；只在 transaction commit 已完成後（orchestration 或 synchronization）publish，不持有 lazy entity。rollback、commit failure、跨日拒絕時零cache寫入。DB已提交但Redis寫失敗時回覆 `outcome=CACHE_UPDATE_FAILED`、`reason=DB_COMMITTED_CACHE_UPDATE_FAILED` 並保留 replacedCount／snapshotId，不能宣稱DB已rollback或全成功；下一次同資料同步可修復cache。既有internal結果可新增此outcome，不新增endpoint／DTO欄位／9090契約。
- [x] **544.5 必須有純讀 consumer。** `AssetService` snapshot detail 由同次DB entity產生同一identity，經port只讀精確內容key；只有root／entry schema、owner／snapshot／日期、完整集合及每個值與DB canonical bytes全符才採鏡像investmentCost，否則用DB。GET不補cache、不外呼、不觸發券商。跨owner／snapshot、壞JSON、少列／多列、manual shares／cost更動、日期roll、row replace與舊afterCommit晚到都不能回舊成本。其他券商直接DB；DTO／BFF／前端呈現欄位不變。
- [x] **544.6 手動save與驗收。** 既有完整PUT仍依Requirement179接受合法shares／investmentCost／transactionDate；內容變更後舊cache不命中，之後inventory以來源shares／cost覆寫、保留transactionDate。不要擴大來源scope、不改owner／日曆／single-flight／排程、不新增交易能力。新增／更新成本覆寫、乘積rounding與overflow、首次／重複／均價變動、pending凍結、filled-trade不重複累加、DB rollback／commit failure、after-commit cache順序與失敗修復、純讀cache consumer精確identity／壞cache／manual mismatch單元或真PG/Redis整合驗證；使用既有相關測試檔擴充，必要新測試不得只鏡像實作。測試後由run-stack只重建受改服務；使用者已授權既有受保護manual inventory sync以同日真來源更新目前資料並讀回DB／Redis／asset detail，不得偽造來源或為驗收放寬日期／行情檢核、不觸發report／mail／trade。

## 驗證

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest=FubonInventoryWriterTest,FubonInventorySyncServiceTest,FubonTradeWriterPostgresTest test
```

其餘依本次實際新增／修改測試執行，至少驗證Redis real-server key/TTL/consumer與真PostgreSQL transaction rollback、成交／庫存順序。部署只重建修改的 business-services（必要時相依BFF重啟使DNS恢復），沿正式main設定，由run-stack完成image rebuild與container recreate，health不能代替業務驗收。從受保護既有manual inventory sync取得同日富邦來源；僅由既有權限讀token且不輸出，驗證009826的DB股數、成本、全部scope aggregate、精確Redis key/value/TTL及既有asset detail一致，重跑不重複加成本。若現有來源時間／交易日閘門拒絕，記錄真實拒絕，不能改時鐘或填假行情。無schema改動，不重產schema.sql。

## 完成報告

2026-11-04 完成實作、獨立審查及正式服務驗收。

- `FubonInventoryWriter` 以富邦回傳的成本均價乘股數、最後 `HALF_UP` 至二位覆寫資料庫總成本並重算彙總；`FubonTradeWriter` 停止新增成本投影，inventory 不再消費舊 pending，避免成交先後或補匯重複加計。歷史投影資料與其他券商行為未改。
- 新增抽象成本快取 port、不可變內容快照及 Redis adapter；只在資料庫成功提交後寫入 owner／snapshot／內容 SHA-256 定址的成本鏡像。`AssetService` 明細純讀且比對同次 DB 完整內容，缺失或錯誤時採 DB，不改全域行情快取或 SRPP 產物。
- 相關單元及真 PostgreSQL／Redis 測試合計 **145 項，0 failure、0 error、0 skipped**。涵蓋來源成本覆寫、精度與溢位、legacy pending 凍結、成交不重複投影、deferred commit failure 零快取、Redis 失敗恢復及實際 `AssetService` consumer。主 agent 已獨立核對測試報告。
- 獨立 spec 審查 critical 0／major 0；唯一 minor 缺字已修正。獨立架構審查完成，通過紀錄確認實作內容未再變動；機械規格檢查 BLOCK 0／CHECK 0，`git diff --check` 無錯誤。
- 依 `run-stack` 僅重建及 recreate `business-services`，服務 healthy，BFF 安全 GET 恢復；沒有重啟 BFF、frontend 或其他服務。實際 46 個環境設定及全部 business 掛載均與 main 權威設定一致，未更改秘密或權限。
- 受保護的正式庫存同步連續兩次 `SUCCESS`，每次更新 **19 筆**。同步前後的新鮮富邦來源一致，所有來源股數與成本、DB、Redis 完整 canonical bytes／SHA 及既有資產明細逐筆相符，並核對整體股票成本彙總。
- 本輪 009826 來源為 **2,000 股、成本均價 10.06 元**，總成本 **20,120.00 元**；DB、Redis、`GET /api/snapshots/{id}` 及既有 9090 資產查詢均一致。Redis 設定 TTL 36 小時，兩次讀回均約 129,599 秒；重跑沒有重複累加成本。原截圖數值是使用者先前手動修正，本次驗收由真實來源重新核對，不將截圖當成自動同步證據。
- 沒有 schema／migration、公開 API、排程或券商交易能力變更；未觸發報告、寄信或交易。正式資料僅經既有庫存同步更新，未以手寫 SQL 修正。
