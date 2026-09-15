# [t435] 台股主檔名稱與即時行情來源隔離

**對應 Requirements:** Requirement 153（非使用者輸入的台股 `stock.name` 只由既有權威名稱流程寫入；即時行情／五檔／富邦庫存名稱不可覆寫）
**前置任務:** t352、t370、t434；保留既有行情與富邦庫存的唯讀邊界。
**Liquibase changeset:** `v1.130.0-tw-stock-master-name-authority.sql`；註冊至 `db.changelog-master.yaml`。它是 idempotent data correction，不得變更 table、column、index 或約束，完成後須重產 `db/schema.sql`。

## 背景

`006208` 在畫面顯示 `FUBON ASSET MANAGEMENT CO LTD F`，但臺灣證券交易所正式簡稱為「富邦台50」。根因是即時行情／五檔與富邦庫存同步把來源的任意非空 `stockName` 視為主檔名稱並直接 upsert；該欄可能是發行公司英文名而非商品名稱。

## 要做什麼

- [ ] **435.1 隔離所有 background source 名稱。** `IntradayOrderBookSnapshotStore` 繼續原子寫入 canonical header 與五檔 levels，但不得再寫 `stock` 主檔；同一 transaction 必先確認既有 `(code, 台股)` 主檔有非空、非代號名稱，缺主檔或無效名稱即 fail closed，header/levels 零寫入。canonical read 的名稱只讀既有主檔，來源 `stockName` 僅供 ingress 格式驗證、不另存。`StockSourceQuery.persistIntradayQuote`、`StockSourceQuery.upsertStockName` 與其 `PricePoller`／`ExistingTwLiveQuoteProvider`／legacy `FubonTwLiveQuoteProvider` 呼叫端，均不得以行情名稱寫入或插入主檔；`FubonMarketDataHistoryStore.persistBasic` 也不得以 Fubon `sourceName` 填補主檔空白或代號名稱。`FubonInventoryWriter` 繼續使用已驗證報價名稱完成庫存 preflight 與持股替換，但不得寫 `stock` 主檔。來源名稱只保留在其既有行情／Fubon observation 或 cache。`AssetService` 的既有使用者快照輸入名稱寫入語意，以及 `StockMasterService` 的明確使用者輸入／權威查詢路徑，均不在本任務變更範圍。
- [ ] **435.2 更正唯一既有列。** 新增並於 master 註冊 `v1.130.0-tw-stock-master-name-authority.sql`；它只在 `stock` 已存在 `code='006208' AND market='台股'` 時把 `name` 更新為 `富邦台50`，SQL 必可重跑。不得 insert，亦不得更新 `asset_class`、`stock_style`、`bond_term`、`underlying_currency` 或任何其他 code/market。
- [ ] **435.3 回歸測試與 schema。** 更新五檔真 PostgreSQL整合測試：先 seed 主檔後新五檔可建立／更新 header 與 levels，但對既有 `stock.name` 不寫入；缺主檔時 header/levels 均為零，較舊或相等快照仍不改任何資料。更新富邦庫存 writer 測試，成功同步也不呼叫 `StockMasterService.upsert`。對 `StockSourceQuery.persistIntradayQuote`、三個舊 `upsertStockName` caller 及 Fubon basic-info 寫入補回歸測試，證明來源名稱不更新或插入主檔，且行情／observation 的既有寫入不變。加入 migration PostgreSQL測試，驗重跑安全、只修正 `006208/台股`，其他主檔行與其 metadata 不變。依專案既有 schema workflow 重產 `db/schema.sql` 並跑 drift test。

## 驗證

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f external-materials-service/pom.xml -Dtest=QuoteDetailSnapshotPersistenceIntegrationTest test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f external-materials-service/pom.xml test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest=FubonInventoryWriterTest test
bash scripts/tests/schema-sql-drift-test.sh
```

以 run-stack 只重建 `external-materials-service` 與 `business-services`，healthcheck 通過後以 PostgreSQL 唯讀 readback 確認 `006208/台股` 為「富邦台50」。不得呼叫任何富邦 manual sync、SDK 或下單／帳務寫入端點。

## 完成報告

（實作者完成後回填實際變更、驗證輸出與任何偏差。）
