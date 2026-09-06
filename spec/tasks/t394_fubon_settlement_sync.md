# [t394] 應收付交割金額同步——僅投影 SDK `3d` 回傳列至在途款

**對應 Requirements:** Requirement 129
**前置任務:** t393 的 snapshot lock；t405 的本次窄範圍 owner／explicit-account 基礎
**Liquibase changeset:** 無（既有權威 schema 的 `bank_deposit.currency` 已為 `VARCHAR(20)`；本次只將 Hibernate Entity 的舊 `length=3` 宣告對齊）

## 可驗證的來源範圍

富邦官方 `query_settlement(account, "3d")` 文件只證實可傳入 `3d` 與回傳欄位；未定義該值的起訖、交易日／曆日、產品範圍或完整性。因此 adapter 固定呼叫 `query_settlement(selected, "3d")`，成功回應的 `coverageStatus` 必須是 `SDK_RANGE_3D_RETURNED_ROWS`，**不得**稱為官方近三日完整窗口或完整未交割帳。

這項同步只依本次合法回應中的列作窄投影：它不清除既有資料、不推斷沒有回傳的交割、不涵蓋期貨、借券或任何未列入回應的產品。所有券商端操作保持唯讀；不新增下單、改單、撤單、匯撥或任何券商端寫入。

## 要做什麼

- [ ] **394.1 嚴格 adapter envelope。** `POST /internal/settlement/read` 不收 account 或 range selector，固定由同次 selected account 呼叫 `query_settlement(..., "3d")`。成功 envelope 必含 `queryDate`、capture 時固定的 `observedAt`、HMAC `accountFingerprint`、真正 JSON boolean `accountBindingExplicit=true`、`coverageStatus=SDK_RANGE_3D_RETURNED_ROWS`、`reason=null` 與 normalized details。`accountBindingExplicit` 僅在 secret selector pair 明確設定、selected account 與全部 raw branch/account 精確相符時為 true；raw account、branch、token 不跨 Python。false、missing、非 boolean、舊 `UNVERIFIED`／`MISSING_SETTLEMENT_RANGE_CONTRACT`、identity／shape／range failure 全部 fail closed，零寫入。

- [ ] **394.2 列驗證與保留規則。** `date` 為來源 query day，`settlement_date` 為來源交割日；兩者嚴格日期且 `settlement_date >= date`，每列 currency 必為 TWD，12 個來源金額皆為 signed exact integer，並驗 `buySettlement <= 0`、`sellSettlement >= 0`、兩者和等於 `totalSettlementAmount`。全欄位 `None` 的合法佔位保留為 `NO_DATA_OBSERVED`，部分 null、重複／矛盾 settlement date、同日或過去日 nonzero 一律拒絕。只加總 `settlementDate > queryDate` 的 AVAILABLE 列；空列、沒有 future 列或任一方向為零均保留既有 target，絕不以零清除。

- [ ] **394.3 窄 owner 與 transaction 邊界。** non-dry-run 前必通過 feature/global/config/broker gates、t405 專用 owner decision 與 `accountBindingExplicit=true`。owner 只由 `FUBON_SYNC_OWNER_EMAIL` 對應的既有 ACTIVE ADMIN 決定，且必須與 configured admin 的 id 與正規化 email 相同；缺少、停用、降權、變更或不一致都不可 fallback。HTTP、名稱／設定預檢都在 transaction 外。`FubonSettlementWriter` 為獨立 `REQUIRES_NEW(timeout=30)` bean；第一個 DB action 必為 `AssetSnapshotMutationLock.lockLatestForFubonConfiguredOwner(expectedOwnerId)`，其後才以 `app_user FOR UPDATE` fresh 重驗 owner，並持鎖至 commit。

- [ ] **394.4 在途 target 與冪等更新。** target business key 是「已鎖 latest snapshot + bank.code=fubon + depositType + currency=TRANSIT_TWD」。權威 `db/schema.sql` 已將 `bank_deposit.currency` 定義為可容納此既定 transit currency 的 `VARCHAR(20)`；本次僅將 Hibernate Entity 的舊 `length=3` 宣告對齊，既不截斷、另造不一致的縮寫，也不新增冗餘 migration。每個非零 direction 最多一筆：`買股待付款` 寫負的 payable，`賣股待收款` 寫正的 receivable。target 缺少時可建立；多筆、錯幣別或無法唯一決定的 target 一律 rollback。每次寫前用 canonical scale-2 金額及 `bank/type/currency` 比較現有 target；完全相同即記 `ALREADY_CURRENT`，不改 child、不重算 aggregate、不 `save`／`flush`。只有資料不同或 target 缺少時才更新／新增；保留 notes，將 `originalAmount`／`annualInterestRate` 設 null，並在同一 transaction 呼叫 `SnapshotAggregateCalculator.recalculate` 與 `saveAndFlush`。不得建立新 snapshot，也不得碰其他 deposit、ledger、holding 或 realized_gain。

- [ ] **394.5 入口、排程與目錄。** 保留 exact-token business `POST /internal/brokers/fubon/settlement-sync?dryRun=true|false`（預設 true）及四個 Asia/Taipei cron `08:00/13:45/19:30/22:00`，各自 single-flight。dry-run 完成相同驗證但不取得 DB lock、不寫入，僅回 sanitized outcome／候選金額／row count。BFF API inventory 將此列標為已串接，說明為「SDK 3d 回傳的 future、nonzero 在途投影；不是完整結算帳」，consumer 明示此 scheduler；不新增 BFF、frontend 或 9090 route。

- [ ] **394.6 驗證。** fake SDK/Python 覆蓋固定 `3d`、explicit binding、日期／null／符號與不能呼叫交易 API。backend/PostgreSQL 覆蓋 disabled 零外呼、owner mismatch/race、dry-run 零鎖零寫、first snapshot lock、create/update、完全相同 no-write、zero preserve、duplicate/currency/precision/commit rollback、aggregate readback。所有 runtime 驗證維持 `FUBON_ENABLED=false` 與 settlement flag false，不登入或查詢真人券商，也不對 manual route 發出非 dry-run POST。

## 完成標準

完整 adapter → strict gate → local projection 的 fixture 路徑、冪等重跑、DB rollback、BFF static inventory 與實際 Docker build/recreate 均通過後，才能把此能力標示為已串接。它永遠不宣稱 `3d` 是完整未交割範圍。
