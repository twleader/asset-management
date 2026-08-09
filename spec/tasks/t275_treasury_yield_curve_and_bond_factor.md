# [t275] 官方美債殖利率曲線與 BOND 專屬利率候選因子

**對應 Requirements:** Requirement 58／65
**前置任務:** t273（回測）、t309（evidence 與 strict asset profile）
**下游整合:** t308（樣本外 promotion 與單一 V13 發布）
**Liquibase changeset:** `v1.92.0-treasury-yield-daily.sql`

## 背景

V12 已支援短／中期雙軌與 18 個因子，但 BOND 沒有利率證據；只用股票型技術指標會遺漏底層債券風險。舊任務預留的 `v1.85.0` 已被使用，且仍以 V9、Yahoo-only 與舊排程筆數描述，不能直接實作。

Primary source 為美國財政部官方 **Daily Treasury Par Yield Curve Rates**。年度 CSV 實際 URL 形狀為 `.../daily-treasury-rates.csv/{year}/all?type=daily_treasury_yield_curve&field_tdr_date_value={year}&page&_format=csv`，欄位含 `3 Mo/5 Yr/10 Yr/30 Yr`；XML feed 為 `https://home.treasury.gov/sites/default/files/interest-rates/yield.xml`。Yahoo `^IRX/^FVX/^TNX/^TYX` 只作整批 fallback。台灣央行政策利率不在範圍。

## 要做什麼

- [ ] **275.1 Curve batch 正規化與 provenance。** `v1.92.0-treasury-yield-daily.sql` 建兩表：
  - `treasury_yield_batch(id bigint identity pk, curve_date date not null, provider varchar(32) not null, source_url text not null, available_at timestamptz not null, availability_basis varchar(40) not null, fetched_at timestamptz not null default now(), complete boolean not null, content_hash varchar(64) not null)`，唯一鍵 `(curve_date,provider,content_hash)`，decision index `(complete,available_at,curve_date desc)`。
  - `treasury_yield_daily(batch_id bigint not null references treasury_yield_batch(id), tenor varchar(8) not null, yield_percent numeric(10,4) not null, source_url text not null, primary key(batch_id,tenor))`；tenor 值域 M3/Y5/Y10/Y30，yield 接受合法 `0.0000`，只拒絕 null、非有限、`<0` 或 `>100`。不得把零利率當 missing。batch header 與四筆 tenor 必須在同一資料庫交易內寫入；任何解析/寫入失敗不得留下 `complete=true` 的半批次。官方列的 `source_url` 可相同；Yahoo proxy 必須逐 tenor 保存各自 `^IRX/^FVX/^TNX/^TYX` URL，API/`RateContext` 回傳 batch manifest，不以未定義的字串拼接冒充 provenance。
  - `complete` 是為了 atomic provider selection 刻意保存的 batch snapshot 狀態；`content_hash` 是 canonical 四 tenor（固定 M3/Y5/Y10/Y30 順序、decimal stripTrailingZeros、missing token 明確）的 idempotency/audit key。這兩個衍生欄位的 denormalization 只用於不可拆分選源與同內容 no-op，禁止拿來評分。
  - 全域行情不帶 owner。changeset 冪等並註冊 master；既有 changeset 不修改。
- [ ] **275.2 官方 adapter 與 immutable batch。** external-materials-service 新增 `TreasuryYieldFetchClient`，用 bounded connect/read timeout 與短 UA 解析官方年度 CSV；XML 只作 current-feed secondary official route。每個 curve date 產生一個 immutable `CurveBatch(curveDate,provider,sourceUrl,availableAt,availabilityBasis,fetchedAt,complete,tenors)`。只有四 tenor 全部有效才 `complete=true`；partial official 可保存為 incomplete 供稽核，但永不進規則。相同 provider/date/content 重抓 no-op，內容改變 append 新 batch，不覆寫舊批次。resolver 另做防禦性檢查：只有存在四個 distinct tenor 且四筆均合法時才接受 `complete=true`；中斷／重試測試須證明不會選半批次。
- [ ] **275.3 Conservative available-at。** 官方 feed 沒有 publication timestamp，不得假設交易日午夜已知。第一次重建某歷史官方 curve 時用 `curveDate+1 day 00:00 America/New_York`，basis=`CONSERVATIVE_NEXT_MIDNIGHT_ET`；current fetch 的 availableAt=`max(fetchedAt,上述邊界)`。同 provider/date 已有舊內容而後來出現修正版時，新 batch availableAt=`fetchedAt`、basis=`OBSERVED_REVISION`。Yahoo proxy 使用 `max(fetchedAt,curveDate 18:00 America/New_York)`、basis=`PROXY_CLOSE_CONSERVATIVE`。所有 decision query 都以 batch `available_at<=decisionInstant` 過濾。
- [ ] **275.4 整批 fallback 與唯一 selection。** 一輪先抓 official；連線、格式、空批次，或欲補的 curve date official 不完整時，才一次抓 Yahoo 四 tenor，組 `YAHOO_PROXY` batch。resolver 只選完整 batch：先取 decision instant 前最新 `curve_date`，同日有多 provider 時 `US_TREASURY` 優先 `YAHOO_PROXY`；不得逐 tenor 選源。完整 fallback 與 partial official 共存時選完整 fallback；日後 complete official 到達後才改選 official。輸出 batch id/provider/completeness，測試證明四 tenor 永遠同 batch；API 即使要求單一 tenor，也只能在先選出完整 batch 後過濾，回應仍帶 batch/provider，禁止逐 tenor 各自選 provider。
- [ ] **275.5 API、落地與排程。** external `GET /internal/macro/treasury-yield?year=YYYY` 回整年度 curve batches；不得以 tenor 控制 source fetch。business `POST /internal/macro/treasury-yield/refresh?year=YYYY` 經 proxy upsert，省略 year 時刷新 current year；歷史 backfill 由 service 逐年呼叫。若內部查詢要 tenor，只能在完整 batch 選定後過濾 response。這兩個 service-to-service endpoint **不進瀏覽器與 BFF**：business `POST` 及 business 端 proxy 由 `AdminGateInterceptor` 的明確 `/internal/macro/treasury-yield/**` pattern 保護；external `GET` 與 external 端 internal proxy 只接受部署環境注入的 `X-Internal-Service-Token`（token 不進 repo／log／API response），缺 token、錯 token、匿名或一般使用者一律 fail-closed 401/403。不得要求不存在的 BFF route 或 BFF `SecurityConfig` matcher；若未來要讓瀏覽器操作，必須另開完整 BFF route、header forwarding 與審查，不得默認存在。固定 cron `0 0 7 * * TUE-SAT` Asia/Taipei 落 business-services，不反向由 external 呼叫 business、不 seed `crawler_schedule`。
- [ ] **275.6 排程列表。** 本次盤點 `SchedulePublicBffController` 為 business 19、external 30、合計 49；新增 business job 後為 20/30/50。實作仍須用 `new ScheduledJobDto` 重算，並同步 javadoc、分組註解、總數與 design，禁止抄 17/29/46 舊值。
- [ ] **275.7 Market-specific decision boundary。** `RateContext` 回 batch/tenor/value/curveDate/provider/sourceManifest（每 tenor 的 source URL）/availableAt/basis/fetchedAt/lagDays/staleReason；不得用單一 sourceUrl 遺失 Yahoo 四個 fallback 請求的 provenance。`lagDays` 另換算為 completed US exchange sessions（使用同一份已知交易日曆；週末與休市日不計），超過 **3 個 completed sessions** 必須 `staleReason` 非空、rate component=`STALE`、不得進 available 分子或 action gate。decision market 的交易日曆缺失／解析為 UNKNOWN 時也必須 `STALE/UNKNOWN_CALENDAR`，不得用 weekday 猜測把長時間未更新曲線視為 fresh。
  - 台灣掛牌外幣債 ETF：只取台股 `decisionInstant` 前 available 的完整 curve；return regression 以台股 adjusted return 為 y，並控制同期 USD/TWD change。
  - 美國掛牌、USD 報價債券 ETF（TLT/IEF/BND/AGG 等）：只取美股 `decisionInstant` 前 available 的完整 curve；return regression 不控制 USD/TWD。使用者基準幣別的 FX factor 可獨立存在，但不得混入 rate beta。
  - 其他市場、quote/underlying currency 不明或 strict bond term UNKNOWN：rate evidence=`APPLICABLE_MISSING`，不得套台股公式或猜 duration。
- [ ] **275.8 Strict term、回歸與候選 tenor。** 使用 t309 的 strict profile；SHORT 候選 M3/Y5、MID 候選 Y5/Y10、LONG 候選 Y10/Y30。以 lag 後 yield change 回歸 adjusted return，輸出 univariate beta、適用時的 FX-controlled beta、n、rolling-window betas、stability/asOf。進 t308 前的結構門檻為 n>=250、至少 3 個互不重疊 250-session windows、各窗 beta 同為負，且非零 beta 絕對值 max/min<=3；未通過回 missing/disclosure，不能傳 0。預先量測六檔 controlled beta 從 00679B 約 -10.94%/1pp 到 00865B 約 -0.01%，不得共用單一係數。
- [ ] **275.9 Candidate 與單一 V13。** rate contribution 先作 ASSET_SPECIFIC candidate；EQUITY=`NOT_APPLICABLE`。具體 tenor、形狀與權重由 t308 full-engine calibration 選擇，holdout/walk-forward 未通過就 disclosure-only。FX 與 rate 量不同風險，不能互相取代或雙重吸收。t275 不改 RULE_VERSION、不單獨部署；最後只由 t308 升一次 `TW_RULES_V13`。

## 驗證

- 官方 CSV/XML fixture：四 tenor、partial、合法 0、null、欄名/日期/decimal；`4.745` 不得變 `47.45`。
- batch idempotency/revision、完整 fallback vs partial official、complete official takeover、同次 selection 四 tenor 同 batch。
- official historic/current/revision 與 proxy available-at 邊界；台股上午不看到當晚資料，美股 decision 不看未 available curve。
- 台股 BOND 控制 USD/TWD；美股 USD BOND 不控制 USD/TWD；UNKNOWN/其他市場 missing。
- 構造序列驗證 lag、beta、三窗穩定門檻、EQUITY N/A、缺值不以 0 拉分。
- 排程清單 20/30/50；manual refresh 後 SQL 列 batch/provider/complete/tenor/date/yield，API 抽查台股長短債與美股債。

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f external-materials-service/pom.xml test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -DextraArgLine=-Dnet.bytebuddy.experimental=true test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml test
docker compose -p asset-management build external-materials-service business-services bff
docker compose -p asset-management up -d --no-deps --force-recreate external-materials-service business-services bff
docker compose -p asset-management ps
docker compose -p asset-management exec -T external-materials-service wget -qO- http://localhost:8080/internal/health
docker compose -p asset-management exec -T business-services wget -qO- http://localhost:8080/actuator/health
docker compose -p asset-management exec -T business-services wget -qO- --header='X-User-Id: 1' --header='X-User-Role: ADMIN' 'http://localhost:8080/internal/macro/treasury-yield/selected?year=2026'
docker compose -p asset-management exec -T business-services wget -qO- --header='X-User-Id: 1' --header='X-User-Role: ADMIN' --post-data='' 'http://localhost:8080/internal/macro/treasury-yield/refresh?year=2026'
docker compose -p asset-management exec -T external-materials-service sh -c 'wget -qO- --header="X-Internal-Service-Token:$INTERNAL_TREASURY_TOKEN" --header="X-User-Role: ADMIN" "http://localhost:8080/internal/macro/treasury-yield?year=2026"'
```

## 完成報告

（回填 official/fallback batch 覆蓋、available-at basis、四 tenor 日期/量級、排程 20/30/50、台美各 BOND beta/n/stability、t308 promotion/rejection、Docker 與通知 baseline 證據。）
