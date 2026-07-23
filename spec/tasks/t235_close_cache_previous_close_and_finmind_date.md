# [t235] 修正休市漲跌缺值與 FinMind 日期錯置

**對應 Requirements:** Requirement 7（市場資料整合：收盤快取完整性與來源交易日期正確性）
**前置任務:** t111
**Liquibase changeset:** 無

## 背景

休市時 `PricePoller` 以 `stock_price_history` 最新收盤同步 Redis。現行 `PriceCacheWriter.syncClosedFromDb` 只沿用 Redis 既有 `previousClose`；cold cache（實機 009804、2885）因沒有該欄，使 `priceChange`／`changePercent` 皆為 null，Dashboard 只顯示股價而隱藏漲跌。另一方面，台股 16:00 FinMind 校正取查詢結果最後一列後，未驗證來源列日期便以呼叫當日 upsert；來源尚停在 T-1 時會把整組 T-1 OHLC 複製成今日資料。

## 要做什麼

- [x] 235.1 `StockSourceQuery` 新增查詢：以 `(stockCode, market, beforeDate)` 取得 `trading_date < beforeDate` 的最近一筆非 null `close_price`。
- [x] 235.2 `PriceCacheWriter.syncClosedFromDb` 一律使用 235.1 取得與 DB 最新 `tradingDate` 對應的昨收，不得沿用 Redis 既有 `previousClose`（可能屬於舊交易日）；再以既有 `changeOrNull`／`changePctOrNull` 衍生漲跌。平盤必須輸出數值 0，僅無任何前一交易日資料時允許 null。
- [x] 235.3 `PriceFetchClient.getTwClosingPriceFromFinMind(stockCode, expectedDate)` 解析最後一列的 `date`；僅日期等於 `expectedDate` 時回傳 `PriceResult`，T-1、缺日期、格式錯誤皆回 empty，且守門發生在 `ClosePersister` 寫 DB／Redis之前。
- [x] 235.4 單元測試覆蓋 cold cache 由 DB 補昨收並算出漲跌／漲跌幅、平盤為 0、Redis 帶有舊交易日 `previousClose` 時仍以 DB 正確昨收覆蓋、DB 僅有最新一筆而查無昨收時三個衍生欄為 null，以及 FinMind 日期相符接受、T-1／缺日期／格式錯誤拒絕。
- [x] 235.5 不新增資料表、endpoint、排程或前端邏輯；既有 Redis key 與 JSON 欄名維持向後相容。

## 驗證

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f external-materials-service/pom.xml test
bash scripts/spec-check.sh
git diff --check
docker compose -p asset-management build --no-cache external-materials-service
docker compose -p asset-management up -d --no-deps --force-recreate external-materials-service
docker exec asset-redis redis-cli --raw GET 'price:台股:009804'
docker exec asset-redis redis-cli --raw GET 'price:台股:2885'
```

部署後兩個 Redis JSON 均須包含 `previousClose`、`priceChange`、`changePercent`；若最新與前一交易日收盤相同，後兩欄須為數值 0。

## 完成報告

完成 `StockSourceQuery.findPreviousCloseBefore`、休市 DB-close 快取的日期一致昨收衍生，以及 FinMind 來源日期守門；新增 8 個回歸案例。Java 21 全套 external-materials-service 測試通過。依 feature worktree 重建並 recreate `external-materials-service`、重啟 BFF，兩者 healthy，BFF actuator UP 且無 connection-refused。實機修復 009804／2885 被舊 FinMind T-1 複製的 2026-07-23 OHLC，Redis 現分別回 `+0.02 / +0.092166%` 與 `+0.20 / +0.316957%`。
