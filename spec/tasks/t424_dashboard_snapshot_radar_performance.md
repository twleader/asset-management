# [t424] Dashboard 快照、交易雷達行情與摘要查詢效能收斂

**對應 Requirements:** Requirement 146（Dashboard 快照、交易雷達行情與摘要查詢效能收斂）
**前置任務:** Task 249（交易雷達手動行情回補）、Task 257（per-owner 最新快照代號範圍）、Task 380（Fubon LIVE quote canonical path）
**Liquibase changeset:** `v1.127.0-dashboard-snapshot-performance.sql`

## 背景

Dashboard summary 的 history 會逐筆碰觸 `AssetSnapshot` 的三個 lazy child collection；三張 child table 的 `snapshot_id` 都是 FK 卻沒有索引，資料成長後每個 history request 都可能做重複掃描。BFF 另外同時呼叫 `/api/market-data/prices`；該路徑把目前快照需要的代號和 Redis 全域價格 index 聯集，並逐筆讀取 stock 主檔名稱，導致 Dashboard 不必要地取得其他使用者／舊快取的行情與 N+1 主檔查詢。

交易雷達的有效集合定義為各 owner 的最新台股持股與台股 alerts 聯集，但現有 collector 沒有要求股票先存在於 stock master。Fubon 只能讀取台股即時行情，不能由 BFF 或 business request-time 直接呼叫。既有 10 秒輪詢每分鐘最多 240 quote calls，所以每輪 40 檔上限是安全契約；目前有效集合小於等於 40 時必定整批呼叫，超過才按排序輪替。

## 要做什麼

- [ ] **424.1 DB index migration。** 新增 `backend/src/main/resources/db/changelog/changes/v1.127.0-dashboard-snapshot-performance.sql`，使用 Liquibase formatted SQL，changeset `steven:v1.127.0-dashboard-snapshot-performance` 必帶 `runInTransaction:false`。依序執行：`CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_stock_holding_snapshot_id ON stock_holding (snapshot_id);`、`CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_fund_holding_snapshot_id ON fund_holding (snapshot_id);`、`CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_bank_deposit_snapshot_id ON bank_deposit (snapshot_id);`。在 `backend/src/main/resources/db/changelog/db.changelog-master.yaml` 最後註冊。不得 drop/rebuild table、改 FK/column 或寫入使用者資料。migration 成功後依 `db/schema.sql` 檔頭三步程序重產 schema，三個 exact index name/column 必須入檔。
- [ ] **424.2 bounded history child loading。** `backend/src/main/java/com/steven/assets/model/AssetSnapshot.java` 的 `deposits`、`funds`、`stocks` 三個既有 `@OneToMany List` 各加 Hibernate `@BatchSize(size = 64)`；不改為 `Set`、不加 `JOIN FETCH`、不改 cascade/orphanRemoval。`AssetService.getAssetHistory()` 的 DTO、分類、owner filter、日期排序和金額算法完全不變。
- [ ] **424.3 bounded stock-name lookup。** `StockRepository` 新增一次取回指定 code 集合的讀方法（不得逐 key query），`StockPriceService.getAllPrices()` 先從 owner 最新快照 collect `(market,code)`，只傳這些 keys 給 `PriceQueryService`，再由單次主檔結果建立名稱 map；`getLiveAssets()` 也用一個 name map，並讓每個 `LiveStockItem` 向後相容地帶入同一 `LivePrice` 的逐筆 `updatedAt`。root `priceUpdatedAt` 只保留作整包最大時間。`PriceQueryService` 新增/使用一個只處理 supplied key set 的 display-price path；不得讀 `price:index:台股`、`price:index:美股`、`price:index:英股`，不得把全域 cache union 回 response。原 `StockPriceDto`、display ordering、Redis-miss history fallback、台股 trusted close gate、live-assets 估值及無名稱 fallback 保持不變。
- [ ] **424.4 stock-master anchored radar scope。** 重寫 `external-materials-service/.../StockSourceQuery.collectTwRadarCodes(Set<String>)` 成單次 SQL：以 `asset_snapshot` 的 `DISTINCT ON (owner_user_id) ... ORDER BY owner_user_id, snapshot_date DESC, id DESC` 取得每位 owner 最新快照台股 holdings，與 `stock_alert` 台股代號 union，再 inner join `stock` on exact `(code, market='台股')`；結果 distinct、主檔存在、符合唯一台股 regex `^[0-9]{4,6}[A-Z]?$`、無 `0000`，寫進 supplied set。保留 read-only JDBC、全租戶 market-cache 語意與 collector failure fail-closed。不得改為全 stock master、不得加入美股/英股、不得讓 BFF/business 接觸 Fubon client。`TwLiveQuoteDispatcher`、Fubon technical 與 push 都只能使用此規則下的共同有效集合，不得另用較寬鬆 regex 擴張；Dispatcher 保留每輪 40 檔與 deterministic round-robin。測試要釘小集合全部傳入 Fubon client，超過 40 只傳 40 且後續輪次前進，以及主檔存在但 regex 不符的代碼不會進入任何 Fubon path。
- [ ] **424.5 BFF summary/realtime 去重。** `bff/.../dashboard/DashboardBffController` 的 `/summary` 從 parallel fan-out 移除 `/api/market-data/prices`，只 parallel 呼叫 `/api/snapshots`、`/api/snapshots/history`、`/api/market-data/market-status`、`/api/market-data/live-assets`。由 `liveAssets.stocks[]` 建立既有 `stockPrices` list：`currentPrice→price`，保留 stockCode/stockName/market/previousClose/priceChange/changePercent/tradingDate/source/quoteStatus，`updatedAt` 使用每筆 `LiveStockItem.updatedAt`（不可使用 root `priceUpdatedAt`），closed 可為 null。之後仍以同一 `SnapshotEnricher.mergePerMarketPrices`、detail request、close data、merged stocks、empty fallback 完成 response。`/realtime` 只 call market-status/live-assets，使用同一 projection。不得變更 `/api/bff/dashboard/summary` 或 `/realtime` 的 JSON field name、前端 API、SSE、BFF tenant relay、DB/Redis access boundary。
- [ ] **424.6 tests。** 新增／調整 backend tests，驗 supplied display keys 不讀 Redis index、stock master lookup 為一次 query、live assets 多持股不逐筆 name lookup且逐筆 `updatedAt` 不被 root 時間覆蓋，並有 PostgreSQL/Liquibase test 驗三個 index。external tests 驗 master joined radar union、0000/non-master／不符唯一 regex 主檔代碼排除與 40-code limit。BFF controller test 驗 summary/realtime 未交換 `/api/market-data/prices` URI、投影結果保留逐筆 `updatedAt` 與既有 `stockPrices` contract、empty/failure fallback/close merge 保持。全部測試只 mock Fubon client，不得呼叫真人 SDK。

## 驗證

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -DextraArgLine=-Dnet.bytebuddy.experimental=true -f backend/pom.xml test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -DextraArgLine=-Dnet.bytebuddy.experimental=true -f external-materials-service/pom.xml test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -DextraArgLine=-Dnet.bytebuddy.experimental=true -f bff/pom.xml test
docker compose -p asset-management build business-services external-materials-service bff
docker compose -p asset-management up -d --no-deps --force-recreate business-services external-materials-service bff
bash scripts/tests/schema-sql-drift-test.sh
```

Docker readback must wait for all three rebuilt services to report healthy, then confirm the three exact indexes with `\d stock_holding`, `\d fund_holding`, `\d bank_deposit`; call the authenticated Dashboard `/api/bff/dashboard/summary` and `/api/bff/dashboard/realtime` and confirm both return JSON. Do not change `.env`, secrets, account data or place any broker order.

## 完成報告

（實作者完成後回填：實際變更、migration/schema evidence、tests、container health、Dashboard readback，及任何偏差。）
