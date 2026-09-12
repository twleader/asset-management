# [t428] 交易雷達首頁真正批次預載與精簡決策

**對應 Requirements:** Requirement 148（交易雷達列表首次載入必以 request-scoped batch input context 取代逐檔完整核心 I/O，保留決策語意與既有 browser/public 邊界）
**前置任務:** t426、t427
**Liquibase changeset:** 無

## 背景

Task 426 已讓 browser 初始請求改走 list、展開才讀一檔 detail，Task 427 修正了 HTTP 200 detail 永久停在 loading 的 Vue proxy identity bug。然而首頁仍慢：現行 `TradingRadarService.getList()` 在每一個 target 的 stream 中呼叫 `buildDecisionCore(..., false)`。雖然這不建立 full DTO/reasons，但仍逐檔串行讀取主檔、500 筆價格、Redis 行情／NAV、配息證據、基本面（其中 public-info 被重讀）、ETF 歷史與共用市場證據；展開只算一檔，所以使用者感覺明細反而較快。

本任務要消除該 request-time N+1，不是縮小 JSON、平行化共用 JPA transaction、加入短期 response cache，或把首頁改成舊的 full/public/9090 path。首頁與明細的 BFF route、DTO、SSE patch mapping 和 owner scope 都已正確，必須保持不變。

## 要做什麼

- [ ] **428.1 建立 list 專用 input context。** 在 business service 建立 request-scoped、immutable 的 list preloader/context（實際類別名稱可調整），由 owner 的全部 eligible `(market,stockCode)` targets 一次建立。每標的 map 必以 exact pair key；market、currency 與 rate/bond evidence 等共用 map 必以其完整自然 key，不得只以股票代號 key。它必涵蓋 stock/profile、最多 500 筆 newest-first 價格歷史、live quote、live ETF NAV、配息 evidence、技術還原 adjustment events、台／美 technical facts/cache 的唯讀 snapshot、基本面 resolved input、ETF premium evidence，以及市場／幣別／instrument 共用 evidence；缺資料以既有 unavailable/fail-soft 語意明確保存。

- [ ] **428.2 實作真正 bounded batch reads。** target loop 前完成以下純讀批次：股票主檔／profile、每 exact pair 的 500 筆價格序列、Redis quote MGET、Redis ETF NAV MGET、配息 evidence、技術還原 adjustment events、每 market 一次的 future-session calendar、台／美 technical fact/cache read snapshot、基本面 observation 與 public-info、ETF premium、market feature、FX/rate/bond-beta。價格 SQL 與其他 SQL 一律 parameterized，並保留每 pair newest-first、500-row 上限與 close-source/fallback 判準。基本面 public-info 在一份 list 只讀一次；financial-quarter、monthly-revenue、valuation-daily、industry-monthly 以 batch as-of 讀取後交給既有 pure factor/as-of resolver。配息 batch 不得退化為每個 target 回呼單檔 `resolve`；rate/bond-beta batch 必須合併其底層 price/dividend/FX/Treasury history read，不得把逐 instrument `loadBatchHistory(...)` 的既有 batch 實作當成已完成。calendar unknown 依既有 fail-closed 語意回 unavailable，且零 request-time 外部日曆抓取。技術 snapshot 僅可讀，不得在 list path 寫 Redis。Redis miss、資料庫缺列與單一 target 解析錯誤均不得補 0、跨 pair 取值、呼叫外部來源或使其他列失敗。

- [ ] **428.3 list evaluator 不得再做單檔 I/O。** 將 `getList()` 改為先 preload、再將 context entries 傳入 list-specific decision evaluator。逐 target evaluation 只能做 map lookup、既有 technical assembly、context 驅動且零寫入的 technical overlay、規則、evidence gate 與 `ListStock` projection；不得在迴圈內呼叫 `findByCodeAndMarket`、`findRecentN`、Redis GET、`PriceQueryService.getLive/getEtfNav`、single dividend/adjustment resolver、single fundamental resolver、現有會 cache read/write 的 `RadarTechnicalResolver.resolve`、market feature 或 rate resolver。不得使用 parallel stream 或跨 thread 共用 JPA/Redis transaction 來掩蓋 N+1。

- [ ] **428.4 相容性與邊界。** 對同一 owner、decision instant、已保存 DB/Redis inputs 與規則版本，list 的每一個可見 scalar、unavailable projection 與 best-score-desc/stockCode-asc sort 必須與既有 full decision 相同。既有 full、snapshot/export 和 `stock` detail 路徑保持原行為，detail 仍只組所請求的一檔。不得新增/修改 BFF 或 9090/public endpoint、wire DTO、SSE mapping、snapshot write、request-time refresh、Fubon/broker/vendor I/O、交易動作或 `.env`/secret。

- [ ] **428.5 回歸測試。** 新增或擴充 backend tests，以至少兩市場、三個 target（含同 code 不同 market）驗證：list 只建立一次 context；public-info 只讀一次；future-session calendar 每 market 只讀一次；list evaluation 開始後零 single-target repository/Redis/fundamental/dividend/adjustment/technical-cache/rate 呼叫與零 technical cache write；batch context 不交叉 `(market,stockCode)`，shared natural-key context 不跨 market/currency/typed rate query 洩漏；list 與 full 的全部 `ListStock` visible scalar、fail-soft row 和排序一致；single-stock detail／full path 沒有走 list context。測試須覆蓋 quote/NAV miss、calendar unknown 與一個 target batch input unavailable 時其他 row 照常回傳。

## 驗證

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -DextraArgLine=-Dnet.bytebuddy.experimental=true -f backend/pom.xml test
docker compose -p asset-management build business-services
docker compose -p asset-management up -d --no-deps --force-recreate business-services
docker inspect --format '{{.State.Health.Status}}' asset-business-services
docker exec asset-bff wget -qO- http://127.0.0.1:8080/actuator/health
curl -fsS 'http://127.0.0.1:9090/api/public/trading-radar/today' | python3 -c 'import json,sys; value=json.load(sys.stdin); assert isinstance(value, dict); print(len(value.get("stocks", [])))'
```

容器驗收由 run-stack 流程執行：feature 驗收只重建 `business-services`，等待 health 後以既有 safe gateway GET 驗證 BFF recovery；merge/push 後再從乾淨、追平 `origin/main` 的 main worktree 重建同一 service。使用既有已登入 browser session 時，對固定 38 個有效 targets 的 `/api/bff/trading-radar/list` 和 legacy `/api/bff/trading-radar` 各連續請求七次、捨棄第一次，記錄 endpoint、時間、bytes、六個 TTFB 與 median；list 六次皆須 `<=800ms`、body `<70KiB`，full median 至少為 list median 的兩倍。沒有已登入 session 時，此 browser TTFB 驗收必標記 pending，不能以 9090 public timing 代替。

## 完成報告

（實作者完成後回填：實際 batch ports、測試類別與結果、feature/main Docker image、authenticated browser TTFB 證據或 pending 原因。）
