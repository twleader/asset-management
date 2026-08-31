# [t409] 盤中走勢的 TWSE MIS session-reference 比較基準

**對應 Requirements:** Requirement 13（股票分析 popup 分時走勢）
**前置任務:** Task 158（前端以 history 自算的舊做法，受本任務覆寫）、Task 372（public readonly bridge 與 quote-detail pure read）

## 背景與決策

既有股票分析 popup 會把分時 tick 與日線 history 在前端組合，找出前一筆日收盤並自行計算「昨收／今日漲跌」。當交易日、來源時間或參考價口徑不一致時，這會使畫面有無法驗證的 comparison。這個任務把 comparison fact 收斂到 server，BFF 把完整 object 原樣傳給前端。

全域來源優先序只在**同一完整且可驗證 fact**內比較：`FUBON > TWSE > Yahoo > other`。generic 台股 LIVE 的 `TwLiveQuoteDispatcher` 保持 240 calls/min、每十秒最多 40 code 的 bounded-fair cursor；未取得本輪 FUBON admission 的 code 才在該輪視為 FUBON 暫不可用，並依 `MIS -> Yahoo` fallback，選擇順序仍是 `Fubon -> MIS -> Yahoo`。

本任務的 `SESSION_REFERENCE` 是另一個 fact：只有 TWSE MIS 的 **同日** `d == targetDate` 和正數 `y` 可驗證。Fubon `previousClose` 未證明等於此 MIS `d/y` session fact，Yahoo 也不能代替；它們都不是候選。五檔則是另一個完整 snapshot fact，MIS 沒有完整同時間五檔，故只能 `FUBON_BOOKS -> YAHOO_TW`，絕不混拼。這些 qualified-fact rules 不反轉全域來源優先序。

## 要做什麼

- [x] **409.1 專用 session reference Redis store。** 新增 `SessionReferencePriceStore` 與 classpath Lua，key 固定為 `price:session-reference:{market}:{code}:{tradingDate}`。payload 必須是 `price`、`source=TWSE_MIS_Y`、`observedAt`、fixed-width `observedAtOrder`；market/code/date/source/price/時間不合法或 future `observedAt` 必須拒絕。Lua 對同 key 只在 `incoming.observedAt > current.observedAt` 時 `SET` + `PEXPIREAT`；equal/older/invalid 不得改 value 或 TTL。這是 raw-source gate，不妨礙其他 canonical DB row 的 missing-Redis repair。

- [x] **409.2 唯一 qualified provider parser。** `PriceFetchClient.fetchTwSessionReference(code,targetDate)` 只從 TWSE MIS item 選 exact code、exact `d`、正 `y`，產生 `TWSE_MIS_Y`。不得將 `z` live price、Fubon `previousClose` 或 Yahoo payload 偽裝成 reference；格式不合、日期不合、非正值一律空結果。

- [x] **409.3 non-public reference-evidence self-heal。** `IntradaySessionQueryService` 供已登入 popup 與其 server-side renderer 使用。tick bucket 一律只讀既有 Redis，絕不呼叫 `IntradayTickRefresher`、FinMind、Yahoo，也不寫 tick/history/DB 或 publish；過去日期也不 cold-start。只有合法台股個股、target 為今日交易日且 **reference-cache miss** 時，才以 `(market,code,date)` bounded single-flight 讀/回寫上述 **TWSE MIS reference evidence**，**不依 tick 完整度**。最多同步等待 8 秒，完成或失敗均 60 秒 cooldown；僅 TWSE MIS，絕不叫 Yahoo/Fubon。非台股、`0000`、非法 code 與過去日期 reference 都不得外呼。

- [x] **409.4 backend server-ready resolver。** `HistoricalDataService.fetchIntradaySession`、`IntradaySessionResolver` 與 `MarketDataController` 回固定 `IntradaySession` object：`tradingDate`、ticks、nullable raw `sessionReferencePrice/sessionReferenceDate/sessionReferenceSource`、server-ready comparison/last/change fields。台股個股只接受 exact `TWSE_MIS_Y` 作 `SESSION_REFERENCE` 並保留其 raw provenance；其他市場與 `0000` 保持 raw strict-prior-history comparison 且 raw reference 三欄為 null。BFF exact route 只 proxy object，不增公開 route/route transform。

- [x] **409.5 frontend fail-closed renderer。** 新增 `normalizeIntradaySession`；只有 `comparisonPrice`、`comparisonKind`、`comparisonSource`、`lastPrice`、`change`、`changePercent` 完整且身分/算術一致才 render。nonempty tuple 還必須有 valid ISO calendar `tradingDate`；`TWSE_MIS_Y` 需完整同日同價 raw reference provenance，raw-history fallback 不得挾帶 raw reference。array wire shape、缺欄、來源/種類矛盾、價格或日期錯誤一律清為 `UNAVAILABLE`/null；前端不再讀日線 history 自算 comparison。

- [x] **409.6 保持 Task 372 public pure read。** 9090/BFF `PublicQuoteMarketDataService` → backend quote-detail → external `/internal/quote-detail` 全程只讀 Redis-first/PG-canonical fallback。external boundary 在任何 cache/DB 前拒絕非台股、`0000` 和不符合 `^[0-9A-Z]{2,10}$` 的 code；不呼叫 Yahoo/Fubon/dispatcher/writer。這個 public path 不可進入 session self-heal。

- [x] **409.7 focused verification。** external unit/real-Redis integration（Docker unavailable 時 `disabledWithoutDocker`）覆蓋 strict-newer 和 TTL 零副作用、date/code isolation、future reject；parser/service 覆蓋 exact `d/y`、Fubon/Yahoo exclusion、market/`0000` gates、current-session reference-cache miss（含 complete ticks）只呼叫 TWSE reference 且零 tick refresh/mutation；backend/BFF 覆蓋 object passthrough；frontend 覆蓋 fail-closed tuple；Task 372 regression 覆蓋 cache miss/invalid code/`0000` 的零 interaction。

## 驗證

```bash
env JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home \
  PATH=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home/bin:$PATH \
  /usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q \
  -f external-materials-service/pom.xml \
  -Dtest=QuoteDetailReadServiceTest,InternalPriceControllerPublicRescanTest,InternalPriceControllerIntradaySessionTest,PriceFetchClientTwSessionReferenceTest,SessionReferencePriceStoreTest,SessionReferencePriceStoreRedisIntegrationTest,IntradaySessionQueryServiceTest,TwFubonOrderBookRoundWriterTest,TwYahooOrderBookFallbackRoundWriterTest test

env JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home \
  PATH=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home/bin:$PATH \
  /usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q \
  -f backend/pom.xml \
  -Dtest=IntradaySessionResolverTest,MarketDataIntradaySessionControllerTest,HistoricalDataIntradaySessionHttpTest,AlertChartRendererIntradaySessionTest,MarketDataQuoteDetailServiceTest test

env JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home \
  PATH=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home/bin:$PATH \
  /usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q \
  -f bff/pom.xml \
  -Dtest=StockAnalysisIntradaySessionRouteTest,StockAnalysisQuoteDetailRouteTest,PublicQuoteMarketDataServiceTest test

npm --prefix frontend test
npm --prefix frontend run build
git diff --check
```

## 完成報告

- [x] external focused suite：`QuoteDetailReadService`／controller pure-read、session store Lua、TWSE parser、current-session reference-cache miss（含 complete ticks）只走 TWSE reference 且零 tick refresh/mutation、bounded session query 與既有 Fubon／Yahoo order-book round writers 均通過；沒有呼叫真實 Yahoo／Fubon／broker API。
- [x] backend focused suite：session resolver、controller、HTTP object contract、renderer 與 quote-detail pure proxy 均通過。
- [x] BFF focused suite：stock-analysis object exact passthrough、quote-detail route 與 `0000` typed unsupported 零 detail call 均通過。
- [x] frontend `npm test`（38 tests）與 production build 均通過；comparison tuple 缺欄、來源/種類矛盾、無效日曆日期及 array wire shape 都 fail closed。
- [x] `git diff --check` 通過。
- [ ] true-Redis integration 已正確標為 `disabledWithoutDocker`（本機 Docker/Testcontainers 不可用）；未把 skip 宣稱為 real-Redis 通過。
- [ ] `scripts/spec-check.sh` 僅被既有跨任務 `db/schema.sql` 與 runtime `asset-postgres` drift 擋住（runtime-only `app_feature`、`fubon_etf_holdings_snapshot`、`fubon_taiex_index_latest`、`fubon_tw_live_quote_response`）；本 Task 未改寫共享 schema dump，待 schema owner 收斂後重跑。
