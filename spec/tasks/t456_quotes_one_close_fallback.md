# [t456] 9090 `GET /api/quotes/one` 即時快取缺漏時回最近收盤價

**對應 Requirements:** Requirement 165
**前置任務:** 無（Requirement 108／115 的 `/api/quotes/one` 聚合已 landed）
**Liquibase changeset:** 無

## 背景

external-materials 的 `price:{market}:{code}` TTL 為 24 小時（`PriceCacheWriter.LIVE_TTL`），週末與連假期間會過期，`/api/quotes/one` 因此回 204，無法提供過去股價。本任務在 BFF 層、僅限 raw 204 時，經 business 既有唯讀端點 `/api/market-data/history/stock` 取最近收盤價組成回應。

## 要做什麼

- [x] 456.1 **BFF 服務** `bff/src/main/java/com/steven/assets/bff/publicquote/PublicQuoteMarketDataService.java`：`one()` 在 `fetchRawOne` 為 empty（raw 204）時，改走新私有方法 `fetchCloseFallback(code, market)`：以 `marketDataClient` GET `/api/market-data/history/stock`（start＝台北今天−30 天、end＝台北今天，使用本類既有 `clock`），解碼為 `List<PricePointDto>`（`bff/.../stockanalysis/dto/PricePointDto.java`，不改該 record），依 Requirement 165 規則組出 `RawLatestQuote`（`source=STOCK_PRICE_HISTORY`、`quoteStatus=CLOSE_FALLBACK`、`closed=true`，漲跌 scale 6 HALF_UP），再交給既有 `enrichSingleRow` 與 global timeout 流程；`enrichSingleRow` 對 `quoteStatus=CLOSE_FALLBACK` 列跳過 Fubon live-response bridge、直接用既有 `fallbackFubon`。取值前濾除 null／≤0 close 與非台股的台北今日列。history 空／錯誤／逾時／無有效 close → `Mono.empty()`（controller 既有 `defaultIfEmpty` 產生 204）。逾時新增 `@Value("${public-quote.timeout.close-fallback-seconds:3}")`，並保留既有 package-private 建構子相容（預設 3 秒）。raw 非 2xx／逾時的錯誤路徑、`list()` 不變。組值邏輯抽成可單元測試的 package-private static 純函式。
- [x] 456.2 **測試**：`bff/src/test/java/.../publicquote/` 新增一支收盤價 fallback 專屬單元測試類（不啟 Spring）覆蓋 Requirement 165 測試條款的全部情境。
- [x] 456.3 **契約**：更新 `docs/openapi/docker-external-api.yaml` `/api/quotes/one`（description、200／204 說明、新增 close-fallback example，以既有 `/one` 200 example 為模板只換 19 欄值；並改 `LatestQuote`／`DetailedLatestQuote` schema description），依專案既有 renderer 重產 Swagger Markdown（地端同步覆寫 `/Users/steven/Project/SRPP/docs/9090 Port API Swagger.md`），並跑既有 OpenAPI contract test。
- [x] 456.3a **文件**：CLAUDE.md 與 `spec/steering/structure.md` 的 BFF 具名例外補述 Requirement 165 fallback。
- [x] 456.4 **驗收**：`mvn test`（BFF 相關）綠燈；`/run-stack` 重建 bff 並 recreate 後，對一檔 Redis 無 key 的標的（或確認當下 key 不存在者）經 `127.0.0.1:9090/api/quotes/one` 取得 200、`quoteStatus=CLOSE_FALLBACK`，且 Redis 命中標的回應仍為原 `LIVE` 內容。

## 完成報告

- BFF：`PublicQuoteMarketDataService.one()` 在 raw 204 時走 `fetchCloseFallback`，組值純函式 `closeFallbackQuote`；`CLOSE_FALLBACK` 列跳過 Fubon bridge。新設定 `public-quote.timeout.close-fallback-seconds`（預設 3）。
- 測試：`mvn test -Dtest='PublicQuote*'` 33 個全綠（新增 `PublicQuoteCloseFallbackTest` 12 個；既有 raw-miss 測試改為斷言 history 為空時仍 204 且只呼叫 history）。
- 契約：OpenAPI `/api/quotes/one` 補 fallback 語意與 `closeFallback` example，Swagger Markdown 重產並同步 SRPP 鏡像；OpenAPI contract test 與 renderer `--check` PASS。
- 架構查核：0 critical／0 major／0 minor。
- 部署驗收（2026-09-27，週日，Redis 台股 price key 全數已過期）：`--no-cache` 重建 bff 並 recreate，healthy、jar 含新類別、env 與重建前一致；9090 `/api/quotes/one?code=0050&market=台股` 回 200、`quoteStatus=CLOSE_FALLBACK`、`source=STOCK_PRICE_HISTORY`、`tradingDate=2026-09-24`、price 112.40／previousClose 112.45，與 `stock_price_history` 逐筆一致，`marketData.chart` 為 AVAILABLE；0056 同樣正確；不存在代號回 204；Redis 命中標的（美股 SGOV）仍回原 `LIVE` 內容。
